package dev.draginventory.client.compass;

import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.lwjgl.glfw.GLFW;

/**
 * 方位条设置界面（像素游戏风）：
 * <ul>
 *   <li>原版 {@link Screen} + 纯 fill/drawString 自绘：像素切角面板、双层描边，
 *       贴合 Minecraft 画风又保留现代游戏 UI 的层次感</li>
 *   <li>六个分页签（外观/布局/显示/标点/动效/颜色），单页内容一屏放下</li>
 *   <li>三套菜单主题（琥珀/青蓝/猩红），外观页签可切换</li>
 *   <li>顶部实时预览：与 HUD 同一渲染路径（{@link CompassWidget#renderStandalone}），
 *       支持拖拽转向 / 模拟滑杆 / 自动摆动 / 宽高自适应缩放</li>
 *   <li><b>全屏拖拽定位模式</b>：布局页签“拖拽定位”直接用鼠标拖动真实 HUD，
 *       附带快捷对齐（水平居中/顶部/垂直居中/底部）与屏宽占比预设（1/4、1/3、1/2、2/3）</li>
 *   <li>动效：面板开合缩放淡入淡出、页签指示条滑动、行悬停高亮条、
 *       开关滑块弹性动画、滑杆手柄拖拽放大、按钮按压闪光</li>
 *   <li>行控件全部无状态化（渲染时读配置、交互时写配置），恢复默认后界面即时同步</li>
 * </ul>
 */
public final class CompassSettingsScreen extends Screen {

    // ==================== 菜单主题 ====================

    /** 主题：暖深灰面板 + 琥珀金强调（默认，参考三角洲行动）。 */
    private static final MenuTheme AMBER =
            new MenuTheme(0xFFD4A056, 0xFFF1C40F, 0xF61E2326, 0xFF2B3236, 0xFF4A555C);
    /** 主题：冷深蓝面板 + 青蓝强调（科技感）。 */
    private static final MenuTheme TECH =
            new MenuTheme(0xFF2FB8A6, 0xFF5EEAD4, 0xF6161C24, 0xFF212A33, 0xFF41525E);
    /** 主题：暖深红面板 + 猩红强调（战术风）。 */
    private static final MenuTheme CRIMSON =
            new MenuTheme(0xFFCE5454, 0xFFFF7B72, 0xF6201719, 0xFF2B2124, 0xFF564249);

    private record MenuTheme(int accent, int accentBright, int panelBg, int panelInner, int panelBorder) {}

    private static MenuTheme theme() {
        return switch (CompassConfig.MENU_THEME.get()) {
            case "tech" -> TECH;
            case "crimson" -> CRIMSON;
            default -> AMBER;
        };
    }

    // 中性色（不随主题变化的文字与控件底色）
    private static final int TITLE_TEXT = 0xFFE0E6ED;    // #E0E6ED 云白标题
    private static final int LABEL_TEXT = 0xFFBDC3C7;    // #BDC3C7 正文
    private static final int VALUE_TEXT = 0xFF7F8C8D;    // #7F8C8D 暗灰次要文字
    private static final int ROW_HOVER = 0x14FFFFFF;
    private static final int CONTROL_BG = 0xFF262D33;
    private static final int CONTROL_BG_HOVER = 0xFF333B42;
    private static final int TRACK_EDGE = 0xFF16191D;
    private static final int KNOB = 0xFFE0E6ED;

    /** 界面版本号（标题栏右侧）。 */
    private static final String VERSION = "v1.5.4";

    /** 行高与内边距（界面像素，2 的倍数对齐像素网格）。 */
    private static final int ROW_H = 17;
    private static final int PAD = 12;

    // ==================== 状态 ====================

    private final CompassWidget preview = new CompassWidget(true);
    private final List<List<Control>> tabs = new ArrayList<>();
    private final long openTime;

    /** 各页签的滚动偏移（内容超高时才非 0）。 */
    private final int[] tabScroll = new int[6];
    private int activeTab;
    private int prevTab;
    private long tabSwitchTime;

    /** 交互目标（拖拽中的滑杆）。 */
    private SliderControl draggingSlider;
    private boolean draggingHeading;
    private boolean draggingPreviewArea;
    private float lastDragX;

    /** 全屏拖拽定位模式。 */
    private boolean positioning;
    private boolean draggingHud;
    private float hudDragLastX, hudDragLastY;

    /** 关闭动画起始时刻（0 = 未在关闭）。 */
    private long closeStart;

    private int panelX, panelY, panelW, panelH;
    private int previewBoxY, previewBoxH;
    private int tabBarY;
    private int contentY, contentH;
    private int footerY;

    private int mouseX, mouseY;

    public static Screen create() {
        return new CompassSettingsScreen();
    }

    private CompassSettingsScreen() {
        super(Component.translatable("draginventory.compass.title"));
        this.openTime = System.currentTimeMillis();
        buildTabs();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * v1.5.2：定位模式跳过原版屏幕背景（菜单模糊 processBlurEffect 会把
     * 已渲染的真实 HUD 一起糊掉 + INWORLD_MENU_BACKGROUND 底图会压暗）——
     * 拖拽定位必须清晰看到真实方位条。普通面板模式保持原版行为。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (positioning) {
            return;
        }
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    /** Esc / 外部关闭：先落盘，随后播放 130ms 收场动画再真正退出。 */
    @Override
    public void onClose() {
        CompassConfig.flush();
        if (closeStart == 0) {
            closeStart = System.currentTimeMillis();
        }
    }

    /** 动画播完后的真实退出。 */
    private void forceClose() {
        CompassConfig.flush();
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (positioning) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                exitPositioning();
            }
            return true; // 定位模式是短时模态：吞掉其余按键避免误触
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (closeStart == 0) {
                onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        panelW = Math.min(336, this.width - 8);
        // 内容区可容纳行数决定面板高度：标题24（含副标题）+ 预览66 + 页签16 + 内容(9行) + 页脚16 + 间距。
        int idealH = 24 + 66 + 16 + ROW_H * 9 + 16 + 22;
        panelH = Math.min(idealH, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int y = panelY + 6;
        // 标题栏（主标题 + 副标题两行）
        y += 24;
        // 预览盒
        previewBoxY = y + 3;
        previewBoxH = 63;
        y = previewBoxY + previewBoxH;
        // 页签栏
        tabBarY = y + 3;
        y = tabBarY + 16;
        // 内容区（吃掉剩余高度，至少留出页脚）
        footerY = panelY + panelH - 20;
        contentY = y + 2;
        contentH = Math.max(ROW_H * 3, footerY - 4 - contentY);
        for (int i = 0; i < tabScroll.length; i++) {
            tabScroll[i] = clampTabScroll(i, tabScroll[i]);
        }
    }

    // ==================== 控件模型 ====================

    /** 单个行控件：渲染 + 鼠标路由，全部无状态（值实时读配置）。 */
    private abstract static class Control {
        abstract int height();

        /** 渲染行背景与标签；返回值供子类画控件。rowBottom 为内容区底边（裁剪用）。 */
        abstract void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom);

        /** 返回 true 表示事件已消费。 */
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            return false;
        }

        /** 本行是否支持单独恢复默认（右侧显示 ↺ 小按钮）。 */
        boolean canReset() {
            return false;
        }

        /** 恢复本行默认值。 */
        void resetToDefault(CompassSettingsScreen s) {}

        /** 行右侧重置按钮几何（x, y, w, h）；仅 canReset 行使用。 */
        static int[] resetGeom(int x, int w, int y) {
            return new int[]{x + w - 11, y + 4, 9, 9};
        }

        boolean drag(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            return false;
        }

        boolean scroll(CompassSettingsScreen s, double delta, double mx, double my, int x, int w, int y) {
            return false;
        }

        void release(CompassSettingsScreen s) {}

        static boolean in(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 开关行（像素滑块开关）。 */
    private static final class BoolControl extends Control {
        private final String labelKey;
        private final ModConfigSpec.BooleanValue config;
        private long changeTime;

        BoolControl(String labelKey, ModConfigSpec.BooleanValue config) {
            this.labelKey = labelKey;
            this.config = config;
        }

        @Override
        int height() {
            return ROW_H;
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            // v1.5.3：部分可见行照常渲染，由内容区 scissor 剪裁
            // （旧版底部守卫会让半滚出的行整行空白，滚动观感突兀）。
            s.hoverRow(g, x, w, y);
            s.drawLabel(g, labelKey, x, y);
            int tw = 30, th = 13;
            int tx = x + w - tw - 2, ty = y + (ROW_H - th) / 2;
            boolean on = config.get();
            // 开关轨道（开启 = 主题色）
            int track = on ? s.accent(0.85f) : CONTROL_BG;
            pixelRect(g, tx, ty, tw, th, track, s.hovered(tx, ty, tw, th) ? s.accentBright(0.8f) : TRACK_EDGE);
            // 滑块（120ms 弹性滑移；v1.5.2 修复：OFF 应停在左侧，旧版参数写反导致
            // ON/OFF 都停在右侧、视觉上“开关显示不正确”）
            float t = anim(changeTime, 120);
            float e = easeOutBack(t);
            float knobX = on
                    ? Mth.lerp(e, tx + 2, tx + tw - 11)
                    : Mth.lerp(e, tx + tw - 11, tx + 2);
            int kx = Math.round(knobX);
            g.fill(kx, ty + 2, kx + 9, ty + th - 2, KNOB);
            g.fill(kx, ty + 2, kx + 9, ty + 3, 0xFFFFFFFF);
            // 状态点
            int dot = on ? 0xFFFFFFFF : 0xFF4A5262;
            g.fill(tx + 4, ty + th / 2, tx + 5, ty + th / 2 + 1, dot);
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int tw = 30, th = 13;
            int tx = x + w - tw - 2, ty = y + (ROW_H - th) / 2;
            if (in(mx, my, tx, ty, tw, th) || in(mx, my, x, y, w, ROW_H)) {
                CompassConfig.set(config, !config.get());
                changeTime = System.currentTimeMillis();
                return true;
            }
            return false;
        }

        @Override
        boolean canReset() {
            return true;
        }

        @Override
        void resetToDefault(CompassSettingsScreen s) {
            CompassConfig.set(config, config.getDefault());
            changeTime = System.currentTimeMillis();
        }
    }

    /** 滑杆行（整型 / 浮点共用，format 负责显示）。 */
    private static final class SliderControl extends Control {
        private final String labelKey;
        private final double min, max;
        private final boolean integer;
        private final ModConfigSpec.ConfigValue<? extends Number> config;
        private final DoubleFunction<String> format;

        SliderControl(String labelKey, ModConfigSpec.IntValue config, int min, int max,
                      DoubleFunction<String> format) {
            this(labelKey, config, min, max, true, format);
        }

        SliderControl(String labelKey, ModConfigSpec.DoubleValue config, double min, double max,
                      DoubleFunction<String> format) {
            this(labelKey, config, min, max, false, format);
        }

        private SliderControl(String labelKey, ModConfigSpec.ConfigValue<? extends Number> config,
                              double min, double max, boolean integer, DoubleFunction<String> format) {
            this.labelKey = labelKey;
            this.config = config;
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.format = format;
        }

        @Override
        int height() {
            return ROW_H;
        }

        double value() {
            return config.get().doubleValue();
        }

        void setFromMouse(CompassSettingsScreen s, double mx, int x, int w) {
            int tx = trackX(s, x, w);
            int tw = trackW(s, x, w);
            double t = Mth.clamp((mx - tx) / tw, 0d, 1d);
            double v = min + (max - min) * t;
            if (integer) {
                v = Math.round(v);
                CompassConfig.set((ModConfigSpec.ConfigValue<Integer>) config, (int) v);
            } else {
                CompassConfig.set((ModConfigSpec.ConfigValue<Double>) config, v);
            }
        }

        /** 轨道几何（标签列之后、右侧数值文字前预留 46px）。 */
        private int trackX(CompassSettingsScreen s, int x, int w) {
            return s.controlX(x, w) + 2;
        }

        private int trackW(CompassSettingsScreen s, int x, int w) {
            int end = x + w - 2 - 46;
            return Math.max(24, end - trackX(s, x, w));
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            s.hoverRow(g, x, w, y);
            s.drawLabel(g, labelKey, x, y);
            int ty = y + (ROW_H - 6) / 2 + 1;
            double v = value();
            boolean active = s.draggingSlider == this;
            // 数值（右侧固定区，不与轨道重叠；拖拽时提亮）
            String text = format.apply(v);
            g.drawString(s.font, text, x + w - 2 - s.font.width(text), y + 5,
                    active ? s.accentBright(1f) : VALUE_TEXT, false);
            // 轨道
            int tx = trackX(s, x, w);
            int tw = trackW(s, x, w);
            boolean hot = s.hovered(tx, ty - 2, tw, 10) || active;
            pixelRect(g, tx, ty, tw, 6, hot ? CONTROL_BG_HOVER : CONTROL_BG, TRACK_EDGE);
            // 填充
            int fillW = (int) Math.round(Mth.clamp((v - min) / (max - min), 0d, 1d) * (tw - 4));
            g.fill(tx + 2, ty + 1, tx + 2 + fillW, ty + 5, s.accent(0.9f));
            // 手柄（像素阶梯造型；拖拽时放大 1px 的“捏住”反馈）
            float t = (float) ((v - min) / (max - min));
            int hx = Math.round(tx + 2 + t * (tw - 4) - 4);
            int grow = active ? 1 : 0;
            g.fill(hx - grow, ty - 3 - grow, hx + 8 + grow, ty + 9 + grow, TRACK_EDGE);
            g.fill(hx + 1 - grow, ty - 2 - grow, hx + 7 + grow, ty + 8 + grow, hot ? 0xFFFFFFFF : KNOB);
            g.fill(hx + 1 - grow, ty - 2 - grow, hx + 7 + grow, ty - 1 - grow, 0xFFFFFFFF);
            g.fill(hx + 3, ty + 1, hx + 5, ty + 4, s.accent(1f));
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            if (in(mx, my, cx, y, cw, ROW_H)) {
                s.draggingSlider = this;
                setFromMouse(s, mx, x, w);
                return true;
            }
            return false;
        }

        @Override
        boolean drag(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            if (s.draggingSlider == this) {
                setFromMouse(s, mx, x, w);
                return true;
            }
            return false;
        }

        @Override
        boolean scroll(CompassSettingsScreen s, double delta, double mx, double my, int x, int w, int y) {
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            if (!in(mx, my, cx, y, cw, ROW_H)) return false;
            double step = (max - min) / (integer ? 40d : 60d);
            if (integer) step = Math.max(1, Math.round(step));
            double v = Mth.clamp(value() + (delta > 0 ? step : -step), min, max);
            if (integer) {
                CompassConfig.set((ModConfigSpec.ConfigValue<Integer>) config, (int) Math.round(v));
            } else {
                CompassConfig.set((ModConfigSpec.ConfigValue<Double>) config, v);
            }
            return true;
        }

        @Override
        boolean canReset() {
            return true;
        }

        @Override
        void resetToDefault(CompassSettingsScreen s) {
            Number d = config.getDefault();
            if (integer) {
                CompassConfig.set((ModConfigSpec.ConfigValue<Integer>) config, d.intValue());
            } else {
                CompassConfig.set((ModConfigSpec.ConfigValue<Double>) config, d.doubleValue());
            }
        }
    }

    /** 循环选择行（皮肤 / 配色 / 步长候选）：[<] 当前值 [>]。 */
    private static final class CyclerControl extends Control {
        private final String labelKey;
        private final List<String> candidates;
        private final IntFunction<Component> namer;
        private final ModConfigSpec.ConfigValue<String> config;
        private final boolean intBacked;
        private final ModConfigSpec.IntValue intConfig;

        CyclerControl(String labelKey, ModConfigSpec.ConfigValue<String> config,
                      List<String> candidates, IntFunction<Component> namer) {
            this.labelKey = labelKey;
            this.config = config;
            this.candidates = candidates;
            this.namer = namer;
            this.intBacked = false;
            this.intConfig = null;
        }

        CyclerControl(String labelKey, ModConfigSpec.IntValue config,
                      List<String> candidates, IntFunction<Component> namer) {
            this.labelKey = labelKey;
            this.intConfig = config;
            this.candidates = candidates;
            this.namer = namer;
            this.config = null;
            this.intBacked = true;
        }

        @Override
        int height() {
            return ROW_H;
        }

        private int index() {
            String current = intBacked ? String.valueOf(intConfig.get()) : config.get();
            int i = candidates.indexOf(current);
            return i >= 0 ? i : 0;
        }

        private void apply(int index) {
            String value = candidates.get(Math.floorMod(index, candidates.size()));
            if (intBacked) {
                CompassConfig.set(intConfig, Integer.parseInt(value));
            } else {
                CompassConfig.set(config, value);
            }
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            s.hoverRow(g, x, w, y);
            s.drawLabel(g, labelKey, x, y);
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int by = y + 2;
            int arrowW = 11;
            int midW = cw - arrowW * 2 - 4;
            // 左右箭头按钮
            s.arrowButton(g, cx, by, arrowW, 13, false);
            s.arrowButton(g, cx + arrowW + midW + 4, by, arrowW, 13, true);
            // 当前值（居中，主题色点缀）
            Component name = namer.apply(index());
            int nameColor = intBacked || labelKey.contains("style") || labelKey.contains("palette")
                    || labelKey.contains("theme")
                    ? s.accent(1f) : TITLE_TEXT;
            int nw = s.font.width(name);
            int nx = cx + arrowW + 4 + (midW - nw) / 2;
            g.drawString(s.font, name, nx, y + 5, nameColor, false);
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int by = y + 2;
            int arrowW = 11;
            int midW = cw - arrowW * 2 - 4;
            if (in(mx, my, cx, by, arrowW, 13)) {
                apply(index() - 1);
                return true;
            }
            if (in(mx, my, cx + arrowW + midW + 4, by, arrowW, 13)) {
                apply(index() + 1);
                return true;
            }
            // 点击中间区域也可循环（右移）
            if (in(mx, my, cx, y, cw, ROW_H)) {
                apply(index() + 1);
                return true;
            }
            return false;
        }

        @Override
        boolean canReset() {
            return true;
        }

        @Override
        void resetToDefault(CompassSettingsScreen s) {
            if (intBacked) {
                CompassConfig.set(intConfig, intConfig.getDefault());
            } else {
                CompassConfig.set(config, config.getDefault());
            }
        }
    }

    /**
     * 颜色覆盖行：[跟随配色] [色块]；点色块展开 H/S/L 三条微调滑杆。
     * v1.5.2 修复：HSL 滑杆补上 drag/release 处理，现在可以用鼠标按住连续拉动
     * （旧版只处理了 click，按下后移动无效果）。
     */
    private static final class ColorControl extends Control {
        private final String labelKey;
        private final ModConfigSpec.IntValue config;
        private final Supplier<Integer> paletteColor;
        private boolean expanded;
        /** 拖拽中的 HSL 分量（-1 = 无）。 */
        private int draggingHsl = -1;
        private long expandTime;

        ColorControl(String labelKey, ModConfigSpec.IntValue config, Supplier<Integer> paletteColor) {
            this.labelKey = labelKey;
            this.config = config;
            this.paletteColor = paletteColor;
        }

        @Override
        int height() {
            return ROW_H + (expanded ? 3 * 12 + 2 : 0);
        }

        private int currentColor() {
            int v = config.get();
            return v >= 0 ? v : paletteColor.get();
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            // v1.5.3：部分可见行照常渲染，由内容区 scissor 剪裁。
            s.hoverRow(g, x, w, y);
            s.drawLabel(g, labelKey, x, y);
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int by = y + 2;
            // 跟随配色按钮
            String follow = I18n.get("draginventory.compass.ui.follow_palette");
            int fw = s.font.width(follow) + 8;
            boolean followHot = s.hovered(cx, by, fw, 13);
            pixelRect(g, cx, by, fw, 13, followHot ? CONTROL_BG_HOVER : CONTROL_BG, s.controlEdge());
            g.drawString(s.font, follow, cx + 4, by + 3, VALUE_TEXT, false);
            // 色块（点击展开 HSL）
            int sx = cx + fw + 6;
            int sw = cw - fw - 6;
            boolean swatchHot = s.hovered(sx, by, sw, 13);
            pixelRect(g, sx, by, sw, 13, 0xFF000000 | currentColor(), swatchHot ? 0xFFFFFFFF : s.controlEdge());
            // 覆盖态角标（左上 3x3 主题色小方块提示“已覆盖”）
            if (config.get() >= 0) {
                g.fill(sx + 2, by + 2, sx + 5, by + 5, s.accent(1f));
            }
            if (expanded) {
                g.fill(sx + sw - 6, by + 3, sx + sw - 3, by + 4, 0xFFFFFFFF);
                g.fill(sx + sw - 6, by + 6, sx + sw - 3, by + 7, 0xFFFFFFFF);
                g.fill(sx + sw - 6, by + 9, sx + sw - 3, by + 10, 0xFFFFFFFF);
            }
            if (expanded) {
                // 展开淡入（150ms）
                float t = anim(expandTime, 150);
                g.pose().pushPose();
                g.pose().translate(0, (1f - t) * 4f, 0);
                try {
                    int subY = y + ROW_H;
                    renderHsl(s, g, x + 26, w - 26, subY, rowBottom, 0, "H", 360, t);
                    renderHsl(s, g, x + 26, w - 26, subY + 12, rowBottom, 1, "S", 100, t);
                    renderHsl(s, g, x + 26, w - 26, subY + 24, rowBottom, 2, "L", 100, t);
                } finally {
                    g.pose().popPose();
                }
            }
        }

        /** 渲染一条 HSL 微调滑杆（component: 0=H 1=S 2=L；alphaIn 为展开淡入系数）。 */
        private void renderHsl(CompassSettingsScreen s, GuiGraphics g, int x, int w,
                               int y, int rowBottom, int component, String tag, int max, float alphaIn) {
            // v1.5.3：剪裁交给内容区 scissor（旧版守卫让部分可见滑杆整条消失）。
            float[] hsl = rgbToHsl(currentColor());
            float value = switch (component) {
                case 0 -> hsl[0] * 360f;
                case 1 -> hsl[1] * 100f;
                default -> hsl[2] * 100f;
            };
            g.drawString(s.font, tag, x, y + 1, VALUE_TEXT, false);
            int tx = x + 10;
            int tw = w - 10 - 30;
            // 轨道（色相行渲染彩虹渐变，S/L 渲染灰度/明度渐变）
            for (int i = 0; i < tw; i++) {
                float t = (float) i / Math.max(1, tw - 1);
                int rgb = switch (component) {
                    case 0 -> hslToRgb(t, Math.max(0.55f, hsl[1]), Math.max(0.5f, hsl[2]));
                    case 1 -> hslToRgb(hsl[0], t, hsl[2]);
                    default -> hslToRgb(hsl[0], 0f, t);
                };
                int a = Math.round(Mth.clamp(alphaIn, 0f, 1f) * 255f);
                g.fill(tx + i, y + 2, tx + i + 1, y + 8, (a << 24) | (rgb & 0xFFFFFF));
            }
            // 手柄（拖拽中的分量手柄加白色描边）
            boolean active = draggingHsl == component;
            int hx = Math.round(tx + (value / max) * (tw - 4));
            g.fill(hx, y, hx + 4, y + 10, TRACK_EDGE);
            g.fill(hx + 1, y + 1, hx + 3, y + 9, active ? 0xFFFFFFFF : KNOB);
            // 数值
            String text = Math.round(value) + (component == 0 ? "\u00B0" : "");
            g.drawString(s.font, text, x + w - 28, y + 1,
                    active ? s.accentBright(1f) : VALUE_TEXT, false);
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int by = y + 2;
            String follow = I18n.get("draginventory.compass.ui.follow_palette");
            int fw = s.font.width(follow) + 8;
            if (in(mx, my, cx, by, fw, 13)) {
                CompassConfig.set(config, -1);
                return true;
            }
            int sx = cx + fw + 6;
            int sw = cw - fw - 6;
            if (in(mx, my, sx, by, sw, 13)) {
                expanded = !expanded;
                expandTime = System.currentTimeMillis();
                return true;
            }
            // HSL 子滑杆交互：命中即写色并进入拖拽（v1.5.2：支持按住连续拉动）
            if (expanded && my > y + ROW_H) {
                int component = hslHit(mx, my, x + 26, w - 26, y + ROW_H);
                if (component >= 0) {
                    draggingHsl = component;
                    s.draggingColorRow = this;
                    applyHsl(component, mx, x + 26, w - 26, y + ROW_H);
                    return true;
                }
            }
            return false;
        }

        /** v1.5.2 新增：按住 HSL 滑杆连续拖动调色。 */
        @Override
        boolean drag(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            if (draggingHsl >= 0 && expanded) {
                applyHsl(draggingHsl, mx, x + 26, w - 26, y + ROW_H);
                return true;
            }
            return false;
        }

        @Override
        void release(CompassSettingsScreen s) {
            draggingHsl = -1;
        }

        @Override
        boolean canReset() {
            return true;
        }

        @Override
        void resetToDefault(CompassSettingsScreen s) {
            CompassConfig.set(config, config.getDefault());
            expanded = false;
        }

        /** 命中三条 HSL 滑杆之一（返回 0/1/2，未命中 -1）。 */
        private int hslHit(double mx, double my, int x, int w, int subY) {
            for (int c = 0; c < 3; c++) {
                int y = subY + c * 12;
                if (my >= y && my < y + 12 && mx >= x && mx < x + w) {
                    return c;
                }
            }
            return -1;
        }

        /** 按鼠标位置写某条 HSL 分量。 */
        private void applyHsl(int component, double mx, int x, int w, int subY) {
            int tx = x + 10;
            int tw = w - 10 - 30;
            float t = (float) Mth.clamp((mx - tx) / Math.max(1, tw - 4), 0d, 1d);
            float[] hsl = rgbToHsl(currentColor());
            switch (component) {
                case 0 -> hsl[0] = t;
                case 1 -> hsl[1] = t;
                default -> hsl[2] = t;
            }
            CompassConfig.set(config, hslToRgb(hsl[0], hsl[1], hsl[2]));
        }
    }

    /** 测试标点按钮行（标点页专用）。 */
    private static final class TestButtonsControl extends Control {
        @Override
        int height() {
            return ROW_H + 4;
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            String[] keys = {
                    "draginventory.compass.ui.test_enemy",
                    "draginventory.compass.ui.test_location",
                    "draginventory.compass.ui.test_item",
                    "draginventory.compass.ui.test_clear",
            };
            int gap = 4;
            int bw = (w - gap * 3) / 4;
            for (int i = 0; i < 4; i++) {
                int bx = x + i * (bw + gap);
                String text = I18n.get(keys[i]);
                boolean hot = s.hovered(bx, y + 2, bw, 14);
                pixelRect(g, bx, y + 2, bw, 14, hot ? CONTROL_BG_HOVER : CONTROL_BG, s.controlEdge());
                int tw = s.font.width(text);
                g.drawString(s.font, text, bx + (bw - tw) / 2, y + 6,
                        i == 3 ? VALUE_TEXT : s.accent(1f), false);
            }
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int gap = 4;
            int bw = (w - gap * 3) / 4;
            for (int i = 0; i < 4; i++) {
                int bx = x + i * (bw + gap);
                if (in(mx, my, bx, y + 2, bw, 14)) {
                    switch (i) {
                        case 0 -> CompassCommands.spawnTestMark(CompassMark.Kind.ENEMY, 15);
                        case 1 -> CompassCommands.spawnTestMark(CompassMark.Kind.LOCATION, 0);
                        case 2 -> CompassCommands.spawnTestMark(CompassMark.Kind.ITEM, -15);
                        default -> CompassHub.clearTestMarks();
                    }
                    return true;
                }
            }
            return false;
        }
    }

    /** 多按钮行（快捷对齐 / 屏宽占比预设共用）：标签 + N 个等宽按钮，按压有主题色闪光。 */
    private static final class ButtonRowControl extends Control {
        private final String labelKey;
        private final String[] buttonKeys;
        private final Runnable[] actions;
        private final long[] pressTimes;

        ButtonRowControl(String labelKey, String[] buttonKeys, Runnable[] actions) {
            this.labelKey = labelKey;
            this.buttonKeys = buttonKeys;
            this.actions = actions;
            this.pressTimes = new long[buttonKeys.length];
        }

        @Override
        int height() {
            return ROW_H + 4;
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            // v1.5.3：部分可见行照常渲染，由内容区 scissor 剪裁。
            s.hoverRow(g, x, w, y);
            s.drawLabel(g, labelKey, x, y);
            int by = y + 2;
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int n = buttonKeys.length;
            int gap = 3;
            int bw = (cw - gap * (n - 1)) / n;
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                int bx = cx + i * (bw + gap);
                String text = I18n.get(buttonKeys[i]);
                boolean hot = s.hovered(bx, by, bw, 14);
                // 按压闪光：220ms 内主题色填充淡出
                float flash = 1f - anim(pressTimes[i], 220);
                int fill = hot ? CONTROL_BG_HOVER : CONTROL_BG;
                if (flash > 0.01f) {
                    fill = blend(fill, s.accent(1f), flash * 0.45f);
                }
                pixelRect(g, bx, by, bw, 14, fill, hot || flash > 0.3f ? s.accentBright(0.7f) : s.controlEdge());
                int tw = s.font.width(text);
                g.drawString(s.font, text, bx + (bw - tw) / 2, by + 3,
                        flash > 0.35f ? 0xFFFFFFFF : TITLE_TEXT, false);
            }
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            int cx = s.controlX(x, w);
            int cw = w - (cx - x) - 2;
            int n = buttonKeys.length;
            int gap = 3;
            int bw = (cw - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                int bx = cx + i * (bw + gap);
                if (in(mx, my, bx, y + 2, bw, 14)) {
                    actions[i].run();
                    pressTimes[i] = System.currentTimeMillis();
                    return true;
                }
            }
            return false;
        }
    }

    /** “拖拽定位”入口按钮行：进入全屏拖拽定位模式（直接拖动真实 HUD）。 */
    private static final class PositionModeControl extends Control {
        @Override
        int height() {
            return ROW_H + 4;
        }

        @Override
        void render(CompassSettingsScreen s, GuiGraphics g, int x, int w, int y, int rowBottom) {
            // v1.5.3：剪裁交给内容区 scissor（旧版守卫让部分可见按钮整行消失）。
            int bx = x + 2, bw = w - 4, by = y + 2, bh = 14;
            boolean hot = s.hovered(bx, by, bw, bh);
            pixelRect(g, bx, by, bw, bh,
                    hot ? s.accent(0.22f) : s.accent(0.12f), s.accent(1f));
            // 四向移动小图标（像素十字箭头）
            int icx = bx + 12, icy = by + bh / 2;
            int c = s.accentBright(1f);
            g.fill(icx - 1, icy - 5, icx + 1, icy + 6, c);
            g.fill(icx - 5, icy - 1, icx + 6, icy + 1, c);
            g.fill(icx - 2, icy - 4, icx + 3, icy - 3, c);  // 上箭头
            g.fill(icx - 2, icy + 3, icx + 3, icy + 4, c);  // 下箭头
            g.fill(icx - 4, icy - 2, icx - 3, icy + 3, c);  // 左箭头
            g.fill(icx + 3, icy - 2, icx + 4, icy + 3, c);  // 右箭头
            String text = I18n.get("draginventory.compass.ui.position_mode");
            int tw = s.font.width(text);
            g.drawString(s.font, text, bx + (bw + 8 - tw) / 2 + 4, by + 3, s.accentBright(1f), false);
        }

        @Override
        boolean click(CompassSettingsScreen s, double mx, double my, int x, int w, int y) {
            if (in(mx, my, x + 2, y + 2, w - 4, 14)) {
                s.enterPositioning();
                return true;
            }
            return false;
        }
    }

    // ==================== 页签内容 ====================

    private void buildTabs() {
        List<Control> appearance = new ArrayList<>();
        appearance.add(new BoolControl("draginventory.compass.cfg.enabled", CompassConfig.ENABLED));
        appearance.add(new CyclerControl("draginventory.compass.cfg.style", CompassConfig.STYLE,
                CompassStyle.all().stream().map(CompassStyle::id).toList(),
                i -> Component.translatable(CompassStyle.all().stream().skip(i).findFirst()
                        .map(CompassStyle::nameKey).orElse(""))));
        appearance.add(new CyclerControl("draginventory.compass.cfg.palette", CompassConfig.PALETTE,
                CompassPalette.all().stream().map(CompassPalette::id).toList(),
                i -> Component.translatable("draginventory.compass.palette."
                        + CompassPalette.all().stream().skip(i).findFirst().map(CompassPalette::id).orElse(""))));
        appearance.add(new CyclerControl("draginventory.compass.cfg.menu_theme", CompassConfig.MENU_THEME,
                List.of("amber", "tech", "crimson"),
                i -> Component.translatable("draginventory.compass.ui.theme."
                        + List.of("amber", "tech", "crimson").get(Math.floorMod(i, 3)))));
        appearance.add(new SliderControl("draginventory.compass.cfg.opacity", CompassConfig.OPACITY,
                0.15, 1.0, v -> Math.round(v * 100) + "%"));
        appearance.add(new BoolControl("draginventory.compass.cfg.degree_symbol", CompassConfig.DEGREE_SYMBOL));
        tabs.add(appearance);

        List<Control> layout = new ArrayList<>();
        layout.add(new PositionModeControl());
        layout.add(new ButtonRowControl("draginventory.compass.cfg.align",
                new String[]{
                        "draginventory.compass.ui.align.center_h",
                        "draginventory.compass.ui.align.top",
                        "draginventory.compass.ui.align.center_v",
                        "draginventory.compass.ui.align.bottom"},
                new Runnable[]{this::alignCenterH, this::alignTop, this::alignCenterV, this::alignBottom}));
        layout.add(new SliderControl("draginventory.compass.cfg.width", CompassConfig.BAR_WIDTH,
                120, 960, this::fmtWidth));
        layout.add(new ButtonRowControl("draginventory.compass.cfg.width_preset",
                new String[]{
                        "draginventory.compass.ui.width.quarter",
                        "draginventory.compass.ui.width.third",
                        "draginventory.compass.ui.width.half",
                        "draginventory.compass.ui.width.two_thirds"},
                new Runnable[]{() -> setWidthRatio(0.25), () -> setWidthRatio(1 / 3d),
                        () -> setWidthRatio(0.5), () -> setWidthRatio(2 / 3d)}));
        layout.add(new SliderControl("draginventory.compass.cfg.offset_x", CompassConfig.OFFSET_X,
                -640, 640, v -> Math.round(v) + "px"));
        layout.add(new SliderControl("draginventory.compass.cfg.offset_y", CompassConfig.OFFSET_Y,
                -640, 640, v -> Math.round(v) + "px"));
        layout.add(new SliderControl("draginventory.compass.cfg.scale", CompassConfig.SCALE,
                0.5, 2.0, v -> String.format("%.2fx", v)));
        tabs.add(layout);

        List<Control> display = new ArrayList<>();
        display.add(new SliderControl("draginventory.compass.cfg.range", CompassConfig.RANGE,
                60, 360, v -> Math.round(v) + "\u00B0"));
        display.add(new CyclerControl("draginventory.compass.cfg.minor_step", CompassConfig.MINOR_STEP,
                List.of("5", "10", "15", "20", "25", "30"), i -> Component.literal(i >= 0 && i < 6
                        ? List.of("5", "10", "15", "20", "25", "30").get(i) + "\u00B0" : "")));
        display.add(new CyclerControl("draginventory.compass.cfg.number_step", CompassConfig.NUMBER_STEP,
                List.of("15", "20", "30", "45", "60", "90"), i -> Component.literal(i >= 0 && i < 6
                        ? List.of("15", "20", "30", "45", "60", "90").get(i) + "\u00B0" : "")));
        display.add(new SliderControl("draginventory.compass.cfg.cardinal_scale", CompassConfig.CARDINAL_SCALE,
                0.8, 2.0, v -> String.format("%.2fx", v)));
        display.add(new BoolControl("draginventory.compass.cfg.show_cardinals", CompassConfig.SHOW_CARDINALS));
        display.add(new BoolControl("draginventory.compass.cfg.show_intercardinals", CompassConfig.SHOW_INTERCARDINALS));
        display.add(new BoolControl("draginventory.compass.cfg.show_numbers", CompassConfig.SHOW_NUMBERS));
        display.add(new BoolControl("draginventory.compass.cfg.cjk_labels", CompassConfig.CJK_LABELS));
        tabs.add(display);

        List<Control> markers = new ArrayList<>();
        markers.add(new BoolControl("draginventory.compass.cfg.markers_enabled", CompassConfig.MARKERS_ENABLED));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_tactical", CompassConfig.MARKERS_TACTICAL));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_death", CompassConfig.MARKERS_DEATH));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_distance", CompassConfig.MARKERS_DISTANCE));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_labels", CompassConfig.MARKERS_LABELS));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_pulse", CompassConfig.MARKERS_PULSE));
        markers.add(new BoolControl("draginventory.compass.cfg.markers_test", CompassConfig.MARKERS_TEST));
        markers.add(new TestButtonsControl());
        tabs.add(markers);

        List<Control> motion = new ArrayList<>();
        motion.add(new SliderControl("draginventory.compass.cfg.smoothness", CompassConfig.SMOOTHNESS,
                CompassHeading.OMEGA_MIN, CompassHeading.OMEGA_MAX, v -> String.format("%.0f", v)));
        motion.add(new BoolControl("draginventory.compass.cfg.snap_assist", CompassConfig.SNAP_ASSIST));
        motion.add(new SliderControl("draginventory.compass.cfg.snap_range", CompassConfig.SNAP_RANGE,
                2, 20, v -> Math.round(v) + "\u00B0"));
        motion.add(new BoolControl("draginventory.compass.cfg.inertia_tilt", CompassConfig.INERTIA_TILT));
        motion.add(new SliderControl("draginventory.compass.cfg.tilt_intensity", CompassConfig.TILT_INTENSITY,
                0.0, 3.0, v -> String.format("%.1f", v)));
        motion.add(new BoolControl("draginventory.compass.cfg.entry_animation", CompassConfig.ENTRY_ANIMATION));
        motion.add(new BoolControl("draginventory.compass.cfg.hide_debug", CompassConfig.HIDE_WITH_DEBUG));
        tabs.add(motion);

        List<Control> colors = new ArrayList<>();
        colors.add(new ColorControl("draginventory.compass.cfg.color_accent", CompassConfig.COLOR_ACCENT,
                () -> CompassWidget.currentPalette().accent()));
        colors.add(new ColorControl("draginventory.compass.cfg.color_text", CompassConfig.COLOR_TEXT,
                () -> CompassWidget.currentPalette().text()));
        colors.add(new ColorControl("draginventory.compass.cfg.color_dim", CompassConfig.COLOR_DIM,
                () -> CompassWidget.currentPalette().dim()));
        colors.add(new ColorControl("draginventory.compass.cfg.color_tick", CompassConfig.COLOR_TICK,
                () -> CompassWidget.currentPalette().tick()));
        colors.add(new ColorControl("draginventory.compass.cfg.color_background", CompassConfig.COLOR_BACKGROUND,
                () -> CompassWidget.currentPalette().background()));
        tabs.add(colors);
    }

    private static final String[] TAB_KEYS = {
            "draginventory.compass.ui.tab.appearance",
            "draginventory.compass.ui.tab.layout",
            "draginventory.compass.ui.tab.display",
            "draginventory.compass.ui.tab.markers",
            "draginventory.compass.ui.tab.motion",
            "draginventory.compass.ui.tab.colors",
    };

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        super.render(g, mouseX, mouseY, partialTick);

        // 全屏拖拽定位模式：只画定位覆盖层，不画面板
        if (positioning) {
            renderPositioning(g);
            return;
        }

        long now = System.currentTimeMillis();
        // 开场动画：150ms 缩放 + 淡入；关闭动画：130ms 缩小 + 淡出后真正退出。
        float openT = anim(openTime, 150);
        float closeT = closeStart == 0 ? 0f : Mth.clamp((now - closeStart) / 130f, 0f, 1f);
        if (closeT >= 1f) {
            forceClose();
            return;
        }
        float scale = (0.95f + 0.05f * openT) * (1f - 0.06f * closeT);
        float alpha = openT * (1f - closeT);
        if (scale != 1f) {
            g.pose().pushPose();
            float cx = panelX + panelW / 2f;
            float cy = panelY + panelH / 2f;
            g.pose().translate(cx, cy, 0);
            g.pose().scale(scale, scale, 1);
            g.pose().translate(-cx, -cy, 0);
        }
        try {
            renderPanel(g, alpha);
        } finally {
            if (scale != 1f) {
                g.pose().popPose();
            }
        }
    }

    private void renderPanel(GuiGraphics g, float alpha) {
        // 面板主体（像素切角 + 双层描边）
        pixelPanel(g, panelX, panelY, panelW, panelH, alpha);

        // ---- 标题栏（金色竖线 + 主标题 + 副标题）----
        int barX = panelX + 9;
        g.fill(barX, panelY + 6, barX + 3, panelY + 21, accent(alpha));
        String title = I18n.get("draginventory.compass.title");
        g.drawString(font, title, barX + 6, panelY + 6, TITLE_TEXT, true);
        g.drawString(font, VERSION, panelX + panelW - PAD - font.width(VERSION), panelY + 6,
                VALUE_TEXT, false);
        String subtitle = I18n.get("draginventory.compass.ui.subtitle");
        g.drawString(font, subtitle, barX + 6, panelY + 16, withAlpha(VALUE_TEXT, alpha), false);
        g.fill(panelX + 8, panelY + 27, panelX + panelW - 8, panelY + 28,
                withAlpha(theme().panelBorder(), alpha));

        // ---- 预览盒 ----
        renderPreviewBox(g, alpha);

        // ---- 页签栏 ----
        renderTabBar(g, alpha);

        // ---- 内容区（带 110ms 上滑 + 淡入切换动画） ----
        float tabT = anim(tabSwitchTime, 110);
        int slide = Math.round((1f - tabT) * 4f);
        // v1.5.3：内容区整体套一层剪裁（绝对 GUI 坐标域，不受姿态变换影响）。
        // 旧版只按 rowBottom 裁底部、顶部完全敞开：行滚动 / 翻页上滑时，
        // 部分滚出的子选项行（标签、左右箭头、开关等）会越界盖住页签栏与预览盒。
        // 开合动画的面板缩放只向内收缩（围绕面板中心 0.95→1.0），内容不会超出
        // 本区域，故绝对坐标剪裁不会误裁；翻页 slide 的 +4px 位移被底部剪裁线
        // 截住，行呈现为“从页脚下缘滑入”的标准滚动观感。
        g.enableScissor(panelX + 8, contentY, panelX + panelW - 8, contentY + contentH);
        g.pose().pushPose();
        g.pose().translate(0, slide, 0);
        renderContent(g, alpha * tabT);
        g.pose().popPose();
        g.disableScissor();

        // ---- 页脚 ----
        renderFooter(g, alpha);
    }

    private void renderPreviewBox(GuiGraphics g, float alpha) {
        int x = panelX + 8, w = panelW - 16;
        pixelPanel(g, x, previewBoxY, w, previewBoxH, alpha * 0.9f, true);
        // v1.5.3：演示条带始终居中于演示盒可用区（水平 + 垂直双轴），
        // 并按可用区宽高自适应缩放（同时考虑配置缩放）。
        // 旧版几何三处错位：(1) 按缩放后宽度定位左上角，但缩放锚点在条带中心（水平漂移）；
        // (2) 缩放 y 轴锚在屏幕原点（垂直上漂）；(3) 可用高未扣除底部控件带 ——
        // 调宽条带 / 大缩放 / 高皮肤时演示会滑出演示盒或压住摆动按钮。
        int barW = Math.max(120, CompassConfig.BAR_WIDTH.get());
        float styleH = Mth.clamp(CompassWidget.currentStyle().widgetHeight(), 40f, 72f);
        float cfgScale = (float) CompassConfig.SCALE.get().doubleValue();
        // 可用区：左右各留 8px；顶部留 1px；底部控件带（摆动按钮/朝向滑杆/提示文字）上方留 14px。
        float availW = w - 16f;
        float availTop = previewBoxY + 1f;
        float availH = previewBoxH - 1f - 14f;
        float fitScale = Math.min(cfgScale, Math.min(availW / barW, availH / styleH));
        fitScale = Math.max(0.15f, fitScale);
        // renderStandalone 以条带中心锚缩放：逻辑原点 = 可用区中心 - 未缩放宽高之半，
        // 缩放后屏幕上条带恰好居中于可用区，与 fitScale 取值无关。
        float px = x + (w - barW) / 2f;
        float py = availTop + (availH - styleH) / 2f;
        // 盒内剪裁：惯性倾斜 / 超大方位字 / 边缘宽标签等极端调整只会在盒内被裁掉，
        // 绝不溢出演示框（剪裁区避开底部控件带）。
        g.enableScissor(x + 1, previewBoxY + 1, x + w - 1, previewBoxY + previewBoxH - 13);
        try {
            preview.renderStandalone(g, LDLibFonts.font(), px, py, barW,
                    System.currentTimeMillis(), fitScale);
        } finally {
            g.disableScissor();
        }
        // 拖拽提示（右下角）
        String hint = I18n.get("draginventory.compass.ui.drag_hint");
        g.drawString(font, hint, x + w - font.width(hint) - 3, previewBoxY + previewBoxH - 11,
                withAlpha(VALUE_TEXT, alpha * 0.7f), false);
        // 自动摆动开关（左下）
        String sway = I18n.get("draginventory.compass.ui.sway_short");
        int sw = font.width(sway) + 8;
        int sx = x + 4, sy = previewBoxY + previewBoxH - 13;
        boolean swayOn = previewSway;
        boolean swayHot = hovered(sx, sy, sw, 12);
        pixelRect(g, sx, sy, sw, 12, swayOn ? accent(0.55f) : (swayHot ? CONTROL_BG_HOVER : CONTROL_BG),
                swayOn ? accent(1f) : controlEdge());
        g.drawString(font, sway, sx + 4, sy + 2, swayOn ? 0xFFFFFFFF : VALUE_TEXT, false);
        // 朝向模拟滑杆（底部中央，避开摆动按钮与提示文字）
        int[] hs = headingSliderGeom();
        if (hs != null) {
            renderHeadingSlider(g, hs[0], previewBoxY + previewBoxH - 11, hs[1], 7);
        }
    }

    /** 朝向模拟滑杆几何（x, w）；空间不足返回 null。三处（渲染/点击/拖拽）共用。 */
    private int[] headingSliderGeom() {
        String hint = I18n.get("draginventory.compass.ui.drag_hint");
        String sway = I18n.get("draginventory.compass.ui.sway_short");
        int x = panelX + 8 + 4 + font.width(sway) + 8 + 8;
        int w = (panelW - 16) - (font.width(sway) + 8) - 8 - 8 - font.width(hint) - 10;
        return w > 60 ? new int[]{x, w} : null;
    }

    private void renderHeadingSlider(GuiGraphics g, int x, int y, int w, int h) {
        float t = Mth.clamp(previewHeading / 360f, 0f, 1f);
        boolean hot = hovered(x, y - 2, w, h + 4) || draggingHeading;
        pixelRect(g, x, y, w, h, hot ? CONTROL_BG_HOVER : CONTROL_BG, TRACK_EDGE);
        int fw = Math.round(t * (w - 4));
        g.fill(x + 2, y + 1, x + 2 + fw, y + h - 1, accent(0.9f));
        int hx = Math.round(x + 2 + t * (w - 4) - 2);
        g.fill(hx, y - 2, hx + 5, y + h + 2, TRACK_EDGE);
        g.fill(hx + 1, y - 1, hx + 4, y + h + 1, hot ? 0xFFFFFFFF : KNOB);
    }

    private void renderTabBar(GuiGraphics g, float alpha) {
        int x = panelX + 8, w = panelW - 16;
        int tabW = (w - 5 * 2) / 6;
        for (int i = 0; i < 6; i++) {
            int tx = x + i * (tabW + 2);
            boolean active = i == activeTab;
            boolean hot = hovered(tx, tabBarY, tabW, 14);
            // 切角描边盒：选中 = 主题色框 + 字，未选 = 灰框灰字。
            if (active) {
                pixelRect(g, tx, tabBarY, tabW, 14, withAlpha(accent(1f), 0.10f * alpha), accent(1f));
            } else {
                pixelRect(g, tx, tabBarY, tabW, 14, hot ? withAlpha(theme().panelInner(), alpha) : 0,
                        controlEdge());
            }
            String name = I18n.get(TAB_KEYS[i]);
            int nw = font.width(name);
            g.drawString(font, name, tx + (tabW - nw) / 2, tabBarY + 3,
                    active ? accent(1f) : VALUE_TEXT, false);
        }
        // 活动页签底部指示条：从旧页签平滑滑到新页签（Mythic 动效）
        int fromX = x + prevTab * (tabW + 2);
        int toX = x + activeTab * (tabW + 2);
        float t = anim(tabSwitchTime, 140);
        int indX = Math.round(Mth.lerp(t, fromX, toX));
        g.fill(indX + 1, tabBarY + 13, indX + tabW - 1, tabBarY + 14, accent(1f));
    }

    private void renderContent(GuiGraphics g, float alpha) {
        int x = panelX + 8, w = panelW - 16;
        int rowBottom = contentY + contentH;
        int y = contentY - tabScroll[activeTab];
        for (Control control : tabs.get(activeTab)) {
            int h = control.height();
            // 行整体在可视区外则跳过（上方滚出 / 下方未入）。
            if (y + h > contentY && y < rowBottom) {
                // v1.5.2：渲染与点击共用同一 ctrlW（旧版部分可见行渲染用全宽、点击用收窄宽，
                // 滚动到底部时点击位置与所见控件错位）。
                int ctrlW = control.canReset() && h == ROW_H && y + ROW_H <= rowBottom ? w - 13 : w;
                control.render(this, g, x, ctrlW, y, rowBottom);
                if (ctrlW == w - 13) {
                    int[] rg = Control.resetGeom(x, w, y);
                    drawResetButton(g, rg[0], rg[1], alpha);
                }
            }
            y += h;
        }
        // 内容超高时的滚动条提示（右侧 2px 细条）。
        int total = contentHeight(activeTab);
        if (total > contentH) {
            int barH = Math.max(12, contentH * contentH / total);
            int barY = contentY + (contentH - barH) * tabScroll[activeTab] / (total - contentH);
            g.fill(panelX + panelW - 6, barY, panelX + panelW - 5, barY + barH,
                    withAlpha(accent(1f), 0.5f * alpha));
        }
    }

    /** 行级重置按钮（小方块 + 左向回退箭头，悬停变主题色）。 */
    private void drawResetButton(GuiGraphics g, int x, int y, float alpha) {
        boolean hot = hovered(x - 1, y - 1, 11, 11);
        int c = hot ? accent(1f) : withAlpha(VALUE_TEXT, alpha);
        pixelRect(g, x - 1, y - 1, 11, 11, hot ? withAlpha(accent(1f), 0.12f) : 0,
                hot ? accent(1f) : controlEdge());
        int cx = x + 4, cy = y + 4;
        g.fill(cx - 1, cy - 3, cx, cy - 2, c);
        g.fill(cx - 2, cy - 2, cx - 1, cy - 1, c);
        g.fill(cx - 3, cy - 1, cx - 2, cy + 1, c);
        g.fill(cx - 2, cy + 1, cx - 1, cy + 2, c);
        g.fill(cx - 1, cy + 2, cx, cy + 3, c);
        g.fill(cx, cy, cx + 5, cy + 1, c);
    }

    private void renderFooter(GuiGraphics g, float alpha) {
        int x = panelX + 8, w = panelW - 16;
        g.fill(x, footerY - 3, x + w, footerY - 2, withAlpha(theme().panelBorder(), alpha * 0.7f));
        // 左侧返回按钮
        String back = I18n.get("draginventory.compass.ui.back");
        int bw = font.width(back) + 12;
        boolean bHot = hovered(x, footerY, bw, 14);
        pixelRect(g, x, footerY, bw, 14, bHot ? CONTROL_BG_HOVER : CONTROL_BG, controlEdge());
        g.drawString(font, back, x + 6, footerY + 3, bHot ? TITLE_TEXT : LABEL_TEXT, false);
        // 中间提示
        String hint = "/com \u00B7 /compass \u00B7 Esc";
        int hw = font.width(hint);
        g.drawString(font, hint, x + (w - hw) / 2, footerY + 3, withAlpha(VALUE_TEXT, alpha * 0.8f), false);
        // 右侧按钮
        String reset = I18n.get("draginventory.compass.ui.reset");
        String done = I18n.get("draginventory.compass.ui.done");
        int dw = font.width(done) + 12;
        int rw = font.width(reset) + 12;
        int dx = x + w - dw;
        int rx = dx - rw - 6;
        // 恢复默认（悬停变红提示破坏性）
        boolean rHot = hovered(rx, footerY, rw, 14);
        pixelRect(g, rx, footerY, rw, 14, rHot ? CONTROL_BG_HOVER : CONTROL_BG, controlEdge());
        g.drawString(font, reset, rx + 6, footerY + 3, rHot ? 0xFFE08585 : VALUE_TEXT, false);
        // 完成（主题色主按钮）
        boolean dHot = hovered(dx, footerY, dw, 14);
        pixelRect(g, dx, footerY, dw, 14, dHot ? accent(0.95f) : accent(0.75f), accent(1f));
        g.drawString(font, done, dx + 6, footerY + 3, 0xFF14181D, false);
    }

    // ==================== 全屏拖拽定位模式 ====================

    /** 进入全屏拖拽定位模式：真实 HUD 照常渲染（LDLib2 层），本界面只画辅助覆盖层。 */
    private void enterPositioning() {
        positioning = true;
        draggingHud = false;
    }

    private void exitPositioning() {
        positioning = false;
        draggingHud = false;
        CompassConfig.flush();
    }

    /** 真实 HUD 的屏幕几何（与 CompassHud.syncLayout + applyScale 的坐标换算一致）。 */
    private float hudScale() {
        return (float) CompassConfig.SCALE.get().doubleValue();
    }

    private float hudCenterX() {
        return width / 2f + CompassConfig.OFFSET_X.get();
    }

    private float hudTopY() {
        return CompassConfig.OFFSET_Y.get() * hudScale();
    }

    private float hudHalfWidth() {
        return CompassConfig.BAR_WIDTH.get() * hudScale() / 2f;
    }

    private float hudHeight() {
        return Mth.clamp(CompassWidget.currentStyle().widgetHeight(), 40f, 72f) * hudScale();
    }

    private void renderPositioning(GuiGraphics g) {
        long now = System.currentTimeMillis();
        // 全屏轻压暗（透出真实 HUD）
        g.fill(0, 0, width, height, 0x590A0C10);
        // 顶部提示带
        String hint = I18n.get("draginventory.compass.ui.position_hint");
        int hw = font.width(hint);
        pixelRect(g, (width - hw) / 2 - 10, 8, hw + 20, 16, 0xE61A2028, accent(1f));
        g.drawString(font, hint, (width - hw) / 2, 13, TITLE_TEXT, false);

        // HUD 命中框（虚线“蚂蚁线”动画：随时间流动）
        float cx = hudCenterX(), top = hudTopY();
        float halfW = hudHalfWidth() + 10f;
        float h = hudHeight() + 14f;
        int x1 = Math.round(cx - halfW), y1 = Math.round(top - 10);
        int x2 = Math.round(cx + halfW), y2 = Math.round(top - 10 + h);
        int dash = 6, gap = 5;
        int period = dash + gap;
        int offset = (int) ((now / 90) % period);
        int ac = draggingHud ? accentBright(1f) : accent(0.9f);
        for (int x = x1 - offset; x < x2; x += period) {
            int a = Math.max(x, x1), b = Math.min(x + dash, x2);
            if (b > a) {
                g.fill(a, y1, b, y1 + 1, ac);
                g.fill(a, y2, b, y2 + 1, ac);
            }
        }
        for (int y = y1 - offset; y < y2; y += period) {
            int a = Math.max(y, y1), b = Math.min(y + dash, y2);
            if (b > a) {
                g.fill(x1, a, x1 + 1, b, ac);
                g.fill(x2, a, x2 + 1, b, ac);
            }
        }
        // 中心十字准星（拖拽时高亮）
        if (draggingHud) {
            g.fill(Math.round(cx) - 4, Math.round(top + h / 2f), Math.round(cx) + 5,
                    Math.round(top + h / 2f) + 1, accentBright(1f));
        }
        // 坐标读数（HUD 下方）
        String coord = "x " + CompassConfig.OFFSET_X.get() + "  y " + CompassConfig.OFFSET_Y.get()
                + "  \u00B7  " + CompassConfig.BAR_WIDTH.get() + "px";
        int cw = font.width(coord);
        g.drawString(font, coord, Math.round(cx - cw / 2f), y2 + 6, accentBright(1f), true);

        // 底部：完成按钮（居中，主题色主按钮）
        String done = I18n.get("draginventory.compass.ui.done");
        int dw = font.width(done) + 24;
        int dx = (width - dw) / 2;
        int dy = height - 26;
        boolean dHot = hovered(dx, dy, dw, 16);
        pixelRect(g, dx, dy, dw, 16, dHot ? accent(0.95f) : accent(0.75f), accent(1f));
        g.drawString(font, done, dx + 12, dy + 4, 0xFF14181D, false);
    }

    /** 快捷对齐：水平居中。 */
    private void alignCenterH() {
        CompassConfig.set(CompassConfig.OFFSET_X, 0);
    }

    /** 快捷对齐：贴顶。 */
    private void alignTop() {
        CompassConfig.set(CompassConfig.OFFSET_Y, 0);
    }

    /** 快捷对齐：垂直居中。 */
    private void alignCenterV() {
        int y = Math.round((height - hudHeight()) / 2f / Math.max(0.01f, hudScale()));
        CompassConfig.set(CompassConfig.OFFSET_Y, Mth.clamp(y, -640, 640));
    }

    /** 快捷对齐：贴底（留 6px 底边距）。 */
    private void alignBottom() {
        int y = Math.round((height - hudHeight() - 6) / Math.max(0.01f, hudScale()));
        CompassConfig.set(CompassConfig.OFFSET_Y, Mth.clamp(y, -640, 640));
    }

    /** 屏宽占比预设（clamp 到 120~960）。 */
    private void setWidthRatio(double ratio) {
        int px = Mth.clamp((int) Math.round(width * ratio), 120, 960);
        CompassConfig.set(CompassConfig.BAR_WIDTH, px);
    }

    /** 宽度滑杆显示：像素 + 屏宽百分比。 */
    private String fmtWidth(double v) {
        int px = (int) Math.round(v);
        int pct = width > 0 ? Math.round(px * 100f / width) : 0;
        return px + "px \u00B7 " + pct + "%";
    }

    // ==================== 鼠标交互 ====================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (positioning) {
            // 完成按钮
            String done = I18n.get("draginventory.compass.ui.done");
            int dw = font.width(done) + 24;
            int dx = (width - dw) / 2;
            int dy = height - 26;
            if (button == 0 && in(mx, my, dx, dy, dw, 16)) {
                exitPositioning();
                return true;
            }
            // 右键任意位置 = 完成
            if (button == 1) {
                exitPositioning();
                return true;
            }
            // 命中 HUD（含 14px 扩展热区）= 开始拖拽
            if (button == 0) {
                float cx = hudCenterX(), top = hudTopY();
                float halfW = hudHalfWidth() + 14f;
                float h = hudHeight() + 18f;
                if (mx >= cx - halfW && mx <= cx + halfW && my >= top - 12 && my <= top - 12 + h) {
                    draggingHud = true;
                    hudDragLastX = (float) mx;
                    hudDragLastY = (float) my;
                    return true;
                }
            }
            return true; // 定位模式下消费其余点击，避免穿透
        }
        if (closeStart != 0) return true; // 关闭动画期间忽略输入
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;

        // 页脚按钮
        int fx = panelX + 8, fw = panelW - 16;
        String back = I18n.get("draginventory.compass.ui.back");
        int bfw = font.width(back) + 12;
        String reset = I18n.get("draginventory.compass.ui.reset");
        String done = I18n.get("draginventory.compass.ui.done");
        int dw = font.width(done) + 12;
        int rw = font.width(reset) + 12;
        int dx = fx + fw - dw;
        int rx = dx - rw - 6;
        if (in(mx, my, fx, footerY, bfw, 14)) {
            // 返回（与完成等效：落盘并关闭）
            onClose();
            return true;
        }
        if (in(mx, my, dx, footerY, dw, 14)) {
            onClose();
            return true;
        }
        if (in(mx, my, rx, footerY, rw, 14)) {
            CompassConfig.resetToDefaults();
            preview.setPreviewHeading(206f);
            return true;
        }

        // 页签栏
        int tx0 = panelX + 8, tw0 = panelW - 16;
        int tabW = (tw0 - 10) / 6;
        for (int i = 0; i < 6; i++) {
            int tx = tx0 + i * (tabW + 2);
            if (in(mx, my, tx, tabBarY, tabW, 14)) {
                if (i != activeTab) {
                    prevTab = activeTab;
                    activeTab = i;
                    tabSwitchTime = System.currentTimeMillis();
                }
                return true;
            }
        }

        // 预览盒：摆动按钮 / 模拟滑杆 / 拖拽转向
        int px = panelX + 8, pw = panelW - 16;
        if (in(mx, my, px, previewBoxY, pw, previewBoxH)) {
            String sway = I18n.get("draginventory.compass.ui.sway_short");
            int sw = font.width(sway) + 8;
            int sx = px + 4, sy = previewBoxY + previewBoxH - 13;
            if (in(mx, my, sx, sy, sw, 12)) {
                previewSway = !previewSway;
                preview.setSway(previewSway);
                return true;
            }
            int[] hs = headingSliderGeom();
            if (hs != null && in(mx, my, hs[0], sy - 1, hs[1], 14)) {
                draggingHeading = true;
                setHeadingFromMouse(mx, hs[0], hs[1]);
                return true;
            }
            // 其余预览区域：拖拽转向（拖拽即停摆动）
            draggingPreviewArea = true;
            lastDragX = (float) mx;
            previewSway = false;
            preview.setSway(false);
            return true;
        }

        // 内容区行
        int cx = panelX + 8, cw = panelW - 16;
        if (in(mx, my, cx, contentY, cw, contentH)) {
            int y = contentY - tabScroll[activeTab];
            for (Control control : tabs.get(activeTab)) {
                int h = control.height();
                if (my >= y && my < y + h) {
                    // 行级重置按钮优先（仅“可重置且完全可见”的标准行显示——
                    // 与渲染条件一致，部分滚出的行不参与重置命中）
                    if (control.canReset() && h == ROW_H && y + ROW_H <= contentY + contentH) {
                        int[] rg = Control.resetGeom(cx, cw, y);
                        if (in(mx, my, rg[0] - 1, rg[1] - 1, 11, 11)) {
                            control.resetToDefault(this);
                            return true;
                        }
                    }
                    int ctrlW = control.canReset() && h == ROW_H ? cw - 13 : cw;
                    if (control.click(this, mx, my, cx, ctrlW, y)) return true;
                    break;
                }
                y += h;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (positioning) {
            if (draggingHud) {
                moveHudBy((float) (mx - hudDragLastX), (float) (my - hudDragLastY));
                hudDragLastX = (float) mx;
                hudDragLastY = (float) my;
            }
            return true;
        }
        if (draggingHeading) {
            int[] hs = headingSliderGeom();
            if (hs != null) setHeadingFromMouse(mx, hs[0], hs[1]);
            return true;
        }
        if (draggingPreviewArea) {
            preview.dragPreview(((float) mx - lastDragX));
            lastDragX = (float) mx;
            return true;
        }
        if (draggingSlider != null) {
            int cx = panelX + 8, cw = panelW - 16;
            int y = rowOf(draggingSlider);
            if (y >= 0) {
                // v1.5.2 修复：拖拽用与点击一致的收窄宽度（旧版传全宽，
                // 一旦开始拖动轨道几何整体右移 13px，数值跳变）
                int ctrlW = draggingSlider.canReset() ? cw - 13 : cw;
                draggingSlider.drag(this, mx, my, cx, ctrlW, y);
            }
            return true;
        }
        if (draggingColorRow != null) {
            int y = rowOf(draggingColorRow);
            if (y >= 0) {
                // 展开态颜色行的 HSL 子滑杆按整行宽渲染（与点击路由一致）
                draggingColorRow.drag(this, mx, my, panelX + 8, panelW - 16, y);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (positioning) {
            draggingHud = false;
            return true;
        }
        draggingHeading = false;
        draggingPreviewArea = false;
        if (draggingColorRow != null) {
            draggingColorRow.release(this);
            draggingColorRow = null;
        }
        if (draggingSlider != null) {
            draggingSlider.release(this);
            draggingSlider = null;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (positioning) {
            // 滚轮直接调宽度：定位模式下顺手微调条带长度
            int step = scrollY > 0 ? 12 : -12;
            int w = Mth.clamp(CompassConfig.BAR_WIDTH.get() + step, 120, 960);
            CompassConfig.set(CompassConfig.BAR_WIDTH, w);
            return true;
        }
        // 内容区滚动（仅内容超高时）
        int cx = panelX + 8, cw = panelW - 16;
        if (in(mx, my, cx, contentY, cw, contentH)) {
            // 先给行内滑杆机会（滚轮微调数值）
            int y = contentY - tabScroll[activeTab];
            for (Control control : tabs.get(activeTab)) {
                int h = control.height();
                if (my >= y && my < y + h) {
                    if (control.scroll(this, scrollY, mx, my, cx, cw, y)) return true;
                    break;
                }
                y += h;
            }
            int total = contentHeight(activeTab);
            if (total > contentH) {
                tabScroll[activeTab] = clampTabScroll(activeTab,
                        tabScroll[activeTab] - (int) Math.round(scrollY) * ROW_H);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    /** 拖拽定位：按屏幕位移更新 HUD 偏移（含屏幕边界与配置范围钳制）。 */
    private void moveHudBy(float dxScreen, float dyScreen) {
        float scale = Math.max(0.05f, hudScale());
        float halfW = hudHalfWidth();
        float h = hudHeight();
        // 水平：中心 1:1 跟随偏移；至少保持 60% 可见
        float minCx = Math.min(halfW * 0.4f, width / 2f);
        float maxCx = width - minCx;
        float targetCx = Mth.clamp(hudCenterX() + dxScreen, minCx, maxCx);
        int offX = Math.round(targetCx - width / 2f);
        offX = Mth.clamp(offX, -640, 640);
        // 垂直：布局层偏移以 y=0 为缩放锚点，视觉位移 = 偏移 * scale
        float targetTop = hudTopY() + dyScreen;
        targetTop = Mth.clamp(targetTop, -h * 0.5f, height - h * 0.5f);
        int offY = Math.round(targetTop / scale);
        offY = Mth.clamp(offY, -640, 640);
        CompassConfig.set(CompassConfig.OFFSET_X, offX);
        CompassConfig.set(CompassConfig.OFFSET_Y, offY);
    }

    /** 行内滑杆拖拽时反查它所在的行 y（用于把几何传回控件）。 */
    private int rowOf(Control target) {
        int y = contentY - tabScroll[activeTab];
        for (Control control : tabs.get(activeTab)) {
            if (control == target) return y;
            y += control.height();
        }
        return -1;
    }

    private void setHeadingFromMouse(double mx, int x, int w) {
        float t = (float) Mth.clamp((mx - x) / w, 0d, 1d);
        previewHeading = t * 360f;
        previewSway = false;
        preview.setPreviewHeading(previewHeading);
    }

    private float previewHeading = 206f;
    private boolean previewSway = true;

    /** 拖拽中的颜色行（HSL 连续调色用）。 */
    private ColorControl draggingColorRow;

    private int clampTabScroll(int tab, int value) {
        int total = contentHeight(tab);
        int max = Math.max(0, total - contentH);
        return Mth.clamp(value, 0, max);
    }

    private int contentHeight(int tab) {
        int h = 0;
        for (Control control : tabs.get(tab)) h += control.height();
        return h;
    }

    // ==================== 像素绘制工具 ====================

    /** 带切角与双层描边的像素面板（minor=true 时不画外发光边，用于内嵌盒）。 */
    private void pixelPanel(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        pixelPanel(g, x, y, w, h, alpha, false);
    }

    private void pixelPanel(GuiGraphics g, int x, int y, int w, int h, float alpha, boolean minor) {
        int bg = withAlpha(theme().panelBg(), alpha);
        int border = withAlpha(theme().panelBorder(), alpha);
        int inner = withAlpha(theme().panelInner(), alpha);
        int c = 3; // 切角尺寸
        // 主体（三段横带构成 2 级阶梯切角）
        g.fill(x + c, y, x + w - c, y + 2, bg);
        g.fill(x + 1, y + 2, x + w - 1, y + h - 2, bg);
        g.fill(x + c, y + h - 2, x + w - c, y + h, bg);
        // 阶梯缺口的填充（两级）
        for (int i = 0; i < 2; i++) {
            g.fill(x + 1 + i, y + 2 - i, x + c - 1 - i, y + 3 - i, bg);
            g.fill(x + w - c + 1 + i, y + 2 - i, x + w - 1 - i, y + 3 - i, bg);
            g.fill(x + 1 + i, y + h - 3 + i, x + c - 1 - i, y + h - 2 + i, bg);
            g.fill(x + w - c + 1 + i, y + h - 3 + i, x + w - 1 - i, y + h - 2 + i, bg);
        }
        // 外描边（沿切角轮廓）
        g.fill(x + c, y, x + w - c, y + 1, border);
        g.fill(x + c, y + h - 1, x + w - c, y + h, border);
        g.fill(x + 1, y + 2, x + 2, y + h - 2, border);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, border);
        // 斜角描边（两级阶梯）
        for (int i = 0; i < 2; i++) {
            g.fill(x + 2 + i, y + 2 - i, x + 3 + i, y + 3 - i, border);
            g.fill(x + w - 3 - i, y + 2 - i, x + w - 2 - i, y + 3 - i, border);
            g.fill(x + 2 + i, y + h - 3 + i, x + 3 + i, y + h - 2 + i, border);
            g.fill(x + w - 3 - i, y + h - 3 + i, x + w - 2 - i, y + h - 2 + i, border);
        }
        if (!minor) {
            // 内衬高光线（顶部受光）
            g.fill(x + c + 1, y + 1, x + w - c - 1, y + 2, inner);
        }
    }

    /** 像素矩形：主体 + 1px 描边（描边色传 0 表示不画）。 */
    private static void pixelRect(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x, y, x + w, y + h, fill);
        if (edge != 0) {
            g.fill(x, y, x + w, y + 1, edge);
            g.fill(x, y + h - 1, x + w, y + h, edge);
            g.fill(x, y, x + 1, y + h, edge);
            g.fill(x + w - 1, y, x + w, y + h, edge);
        }
    }

    /** 左右箭头小按钮（"<" / ">"，像素阶梯箭头）。 */
    private void arrowButton(GuiGraphics g, int x, int y, int w, int h, boolean right) {
        boolean hot = hovered(x, y, w, h);
        pixelRect(g, x, y, w, h, hot ? CONTROL_BG_HOVER : CONTROL_BG, hot ? accentBright(0.9f) : controlEdge());
        int cx = x + w / 2, cy = y + h / 2;
        int c = hot ? accentBright(1f) : 0xFF9AA3B2;
        if (!right) {
            g.fill(cx - 1, cy - 3, cx, cy - 2, c);
            g.fill(cx - 2, cy - 2, cx - 1, cy - 1, c);
            g.fill(cx - 3, cy - 1, cx - 2, cy + 1, c);
            g.fill(cx - 2, cy + 1, cx - 1, cy + 2, c);
            g.fill(cx - 1, cy + 2, cx, cy + 3, c);
        } else {
            g.fill(cx, cy - 3, cx + 1, cy - 2, c);
            g.fill(cx + 1, cy - 2, cx + 2, cy - 1, c);
            g.fill(cx + 2, cy - 1, cx + 3, cy + 1, c);
            g.fill(cx + 1, cy + 1, cx + 2, cy + 2, c);
            g.fill(cx, cy + 2, cx + 1, cy + 3, c);
        }
    }

    /** 行悬停：底色 + 左侧主题色高亮条（滑入感的静态近似）。 */
    private void hoverRow(GuiGraphics g, int x, int w, int y) {
        if (hovered(x, y, w, ROW_H)) {
            g.fill(x, y, x + w, y + ROW_H, ROW_HOVER);
            g.fill(x, y + 2, x + 2, y + ROW_H - 2, accent(0.85f));
        }
    }

    private void drawLabel(GuiGraphics g, String key, int x, int y) {
        g.drawString(font, I18n.get(key), x + 4, y + 5, LABEL_TEXT, false);
    }

    /** 控件区起点 x（标签列宽 = 面板宽 * 0.42，至少 118）。 */
    private int controlX(int x, int w) {
        return x + Math.max(118, w * 42 / 100);
    }

    private boolean hovered(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** 鼠标事件坐标命中矩形（double 参数版本，事件处理器共用）。 */
    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 当前菜单主题色（带透明度合成）。 */
    private int accent(float alpha) {
        return withAlpha(theme().accent(), alpha);
    }

    /** 主题高亮色（按钮/箭头悬停、拖拽反馈）。 */
    private int accentBright(float alpha) {
        return withAlpha(theme().accentBright(), alpha);
    }

    private int controlEdge() {
        return theme().panelBorder();
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round((color >>> 24) * Mth.clamp(alpha, 0f, 1f));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** ARGB 混色（用于按压闪光）。 */
    private static int blend(int from, int to, float t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        int fa = (from >>> 24), ta = (to >>> 24);
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int a = Math.round(fa + (ta - fa) * t);
        int r = Math.round(fr + (tr - fr) * t);
        int gg = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /** ease-out 动画进度 [0,1]。 */
    private static float anim(long start, int durationMs) {
        if (durationMs <= 0) return 1f;
        float t = (System.currentTimeMillis() - start) / (float) durationMs;
        t = Mth.clamp(t, 0f, 1f);
        return 1f - (1f - t) * (1f - t);
    }

    /** ease-out-back（轻微过冲的弹性缓动，开关滑块用）。 */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    // ==================== HSL 颜色转换 ====================

    /** RGB -> HSL（h/s/l 均为 0..1）。 */
    private static float[] rgbToHsl(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float gg = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(gg, b));
        float min = Math.min(r, Math.min(gg, b));
        float l = (max + min) / 2f;
        float d = max - min;
        float h = 0f, s = 0f;
        if (d > 1e-5f) {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = (gg - b) / d + (gg < b ? 6f : 0f);
            else if (max == gg) h = (b - r) / d + 2f;
            else h = (r - gg) / d + 4f;
            h /= 6f;
        }
        return new float[]{h, s, l};
    }

    /** HSL（0..1） -> RGB。 */
    private static int hslToRgb(float h, float s, float l) {
        h = Mth.clamp(h, 0f, 1f);
        s = Mth.clamp(s, 0f, 1f);
        l = Mth.clamp(l, 0f, 1f);
        if (s < 1e-5f) {
            int v = Math.round(l * 255f);
            return (v << 16) | (v << 8) | v;
        }
        float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
        float p = 2f * l - q;
        int r = Math.round(hueToRgb(p, q, h + 1f / 3f) * 255f);
        int gg = Math.round(hueToRgb(p, q, h) * 255f);
        int b = Math.round(hueToRgb(p, q, h - 1f / 3f) * 255f);
        return (r << 16) | (gg << 8) | b;
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }
}
