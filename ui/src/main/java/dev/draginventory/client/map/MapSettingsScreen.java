package dev.draginventory.client.map;

import static dev.draginventory.client.map.FactoryMapUI.*;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.lwjgl.glfw.GLFW;

/**
 * 战术地图设置界面（原版 {@link Screen} 全自绘，与地图共用 {@link FactoryMapUI} 战术词汇，
 * 紧凑版六页签收敛为五页签）。
 *
 * <p>居中面板布局（逻辑坐标 = 画布空间，uiScale 策略同 {@link FactoryMapScreen}：
 * 画布小于 640×360 时整体等比缩小）：
 * <ul>
 *   <li>面板 W=min(460, 画布宽-20)、H=min(300, 画布高-20)，tacticalPanel + 左侧 ACCENT 强调边；
 *       标题栏 "HERRA / 地图设置" + 右上 X 关闭；</li>
 *   <li>页签栏（y+28..48）：楼层 / 图层 / 视觉 / 行为 / 标点，活动页签底部 2px ACCENT 指示条；</li>
 *   <li>预览区（y+52..152，高 100）：左 60% 迷你地图（独立 {@link MapCoordinateTransform}——
 *       不走 {@code session.transform}，那会改写会话的缩放/中心状态；scissor 裁剪），
 *       右 40% 九语义色样例（图层关闭的语义画固定深色底 + 角上红色小斜杠），
 *       底部一行当前读数（不透明度 + 滤镜预设，DIM）；</li>
 *   <li>内容区（y+160..H-32，固定不滚动）：按 {@code currentTab} 渲染对应控件
 *       （楼层页 = 自动层说明 + 层高跨度/扫描半径滑条——v2.5.2 删除手动层；视觉页 =
 *       6 滤镜预设块 + 不透明度 + 网格/恢复/开图清屏开关；行为页 = 跟随 + 小地图
 *       开关/缩放/尺寸 + 缩放范围——v2.5.2 重排后小地图尺寸滑条完整可见；标点页 =
 *       风格块 + 距离/倒计时 + 玩家图标大小滑条（v2.6.0））；</li>
 *   <li>底部按钮行（H-28）：恢复全部默认 / 完成。</li>
 * </ul></p>
 *
 * <p>所有改动经 {@link MapConfig#set} 写回（journal 对抗保存竞态，client tick 防抖落盘），
 * 关屏时 {@link MapConfig#flush} 兜底立即写盘。预览瓦片是独立 {@link FactoryMapTiles}
 * 实例（独立 LRU 池），小视口下每帧烘焙预算开销可控。</p>
 */
public final class MapSettingsScreen extends Screen {
    /** 页签语言键后缀（楼层 / 图层 / 视觉 / 行为 / 标点）。 */
    private static final String[] TAB_KEYS = {"tab_floor", "tab_layers", "tab_vision", "tab_behavior", "tab_markers"};

    /** 标点风格合法值（与 MapConfig.MARKER_STYLE 约定一致）。 */
    private static final String STYLE_TACTICAL = "tactical", STYLE_MINIMAL = "minimal";

    /** 滑条轨道宽（逻辑像素）与滑条行高。 */
    private static final int TRACK_W = 60, SLIDER_H = 18;

    /** 图层开关定义（与地图左面板同序：walls/floor/water/stairs/doors/decor/upper/lower）。 */
    private record LayerToggle(String key, ModConfigSpec.BooleanValue value) {}

    private static final LayerToggle[] LAYER_TOGGLES = {
            new LayerToggle("layer_walls", MapConfig.SHOW_WALLS),
            new LayerToggle("layer_floor", MapConfig.SHOW_FLOOR),
            new LayerToggle("layer_water", MapConfig.SHOW_WATER),
            new LayerToggle("layer_stairs", MapConfig.SHOW_STAIRS),
            new LayerToggle("layer_doors", MapConfig.SHOW_DOORS),
            new LayerToggle("layer_decor", MapConfig.SHOW_DECOR),
            new LayerToggle("layer_upper", MapConfig.SHOW_UPPER),
            new LayerToggle("layer_lower", MapConfig.SHOW_LOWER),
    };

    /**
     * 滑条描述：label 语言键 + 范围/步进 + 读写闭包（set 内经 {@link MapConfig#set} 反写配置）
     * + 值格式化。绘制与拖动共用同一实例。
     */
    private record SliderHelper(String key, double min, double max, double step,
            DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> format) {

        /** 当前配置值 → 轨道比例（0..1，越界钳制）。 */
        double fraction() { return Mth.clamp((get.getAsDouble() - min) / (max - min), 0.0, 1.0); }

        /** 轨道比例 → 吸附到步长格点的值（钳回范围；三位小数四舍五入消除浮点尾差）。 */
        double valueAt(double fraction) {
            double f = Mth.clamp(fraction, 0.0, 1.0);
            long steps = Math.round((max - min) * f / step);
            double v = Math.round((min + steps * step) * 1000.0) / 1000.0;
            return Mth.clamp(v, min, max);
        }
    }

    /** 滑条行几何（一处定义，绘制与命中共用）：轨道锚在行右端，命中区在轨道左右各外扩 12px。 */
    private record SliderRow(int id, int x, int y, int w, SliderHelper helper) {
        int trackX() { return x + w - TRACK_W - 2; }
        boolean hit(int mx, int my) { return rowHit(mx, my, trackX() - 12, y, TRACK_W + 24, SLIDER_H); }
    }

    private static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String fi(double v) { return Integer.toString((int) Math.round(v)); }

    // ==================== 滑条实例（id 0..7 供拖动状态编码） ====================

    private static final SliderHelper SPAN_SLIDER = new SliderHelper("span", 1, 16, 1,
            () -> MapConfig.LAYER_SPAN.get(), v -> MapConfig.set(MapConfig.LAYER_SPAN, (int) Math.round(v)), MapSettingsScreen::fi);
    private static final SliderHelper RADIUS_SLIDER = new SliderHelper("scan_radius", 64, 512, 4,
            () -> MapConfig.SCAN_RADIUS.get(), v -> MapConfig.set(MapConfig.SCAN_RADIUS, (int) Math.round(v)), MapSettingsScreen::fi);
    private static final SliderHelper OPACITY_SLIDER = new SliderHelper("opacity", 0.3, 1.0, 0.05,
            () -> MapConfig.OPACITY.get(), v -> MapConfig.set(MapConfig.OPACITY, v), MapSettingsScreen::f2);
    private static final SliderHelper ZOOM_MIN_SLIDER = new SliderHelper("zoom_min", 1.08, 2.0, 0.05,
            () -> MapConfig.ZOOM_MIN.get(), v -> MapConfig.set(MapConfig.ZOOM_MIN, v), MapSettingsScreen::f2);
    private static final SliderHelper ZOOM_MAX_SLIDER = new SliderHelper("zoom_max", 2.0, 16.0, 0.25,
            () -> MapConfig.ZOOM_MAX.get(), v -> MapConfig.set(MapConfig.ZOOM_MAX, v), MapSettingsScreen::f2);
    private static final SliderHelper MINIMAP_ZOOM_SLIDER = new SliderHelper("minimap_zoom", 0.5, 4.0, 0.25,
            () -> MapConfig.MINIMAP_ZOOM.get(), v -> MapConfig.set(MapConfig.MINIMAP_ZOOM, v), MapSettingsScreen::f2);
    private static final SliderHelper MINIMAP_SIZE_SLIDER = new SliderHelper("minimap_size", 64, 256, 8,
            () -> MapConfig.MINIMAP_SIZE.get(), v -> MapConfig.set(MapConfig.MINIMAP_SIZE, (int) Math.round(v)),
            MapSettingsScreen::fi);
    /** v2.6.0：玩家位置图标大小（0.4~1.0 倍率，1.0 = 默认 = 上限）；滑条值按百分比展示，预览实时可见。 */
    private static final SliderHelper PLAYER_SIZE_SLIDER = new SliderHelper("player_size", 0.4, 1.0, 0.05,
            () -> MapConfig.PLAYER_MARKER_SIZE.get(), v -> MapConfig.set(MapConfig.PLAYER_MARKER_SIZE, v),
            v -> Math.round(v * 100) + "%");

    // ==================== 状态 ====================

    private final FactoryMapSession previewSession;
    private final FactoryMapTiles previewTiles = new FactoryMapTiles();
    private final FactoryMapPalette previewPalette = new FactoryMapPalette();
    /** 当前页签（0 楼层 / 1 图层 / 2 视觉 / 3 行为 / 4 标点）。 */
    private int currentTab;
    /** 正在拖动的滑条 id（0..7；-1 = 无拖动）。 */
    private int draggingSlider = -1;
    private float uiScale;
    private int canvasW, canvasH, px, py, panelW, panelH;

    private MapSettingsScreen(FactoryMapSession session) {
        super(Component.translatable("draginventory.map.settings.title"));
        this.previewSession = session;
    }

    public static MapSettingsScreen create() {
        return new MapSettingsScreen(new FactoryMapSession(Minecraft.getInstance()));
    }

    @Override protected void init() {
        // 与 FactoryMapScreen 相同的 uiScale 策略：画布小于 640×360 时等比缩小整套 UI。
        uiScale = Math.min(1f, Math.min(width / 640f, height / 360f));
        canvasW = Math.round(width / uiScale);
        canvasH = Math.round(height / uiScale);
        panelW = Math.min(460, canvasW - 20);
        panelH = Math.min(300, canvasH - 20);
        px = (canvasW - panelW) / 2;
        py = (canvasH - panelH) / 2;
        draggingSlider = -1;
    }

    @Override public void tick() {
        if (minecraft != null && minecraft.level != null) previewSession.tick(minecraft);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int mx = (int) (mouseX / uiScale), my = (int) (mouseY / uiScale);
        g.pose().pushPose();
        g.pose().scale(uiScale, uiScale, 1);
        g.fill(0, 0, canvasW, canvasH, 0xB4060F14); // 半透明压暗底幕（世界仍渲染在屏后）
        tacticalPanel(g, px, py, panelW, panelH, true);
        text(g, "HERRA / " + tr("settings_title"), px + 12, py + 8, ACCENT);
        strokeButton(g, px + panelW - 34, py + 4, 30, 16, "X", mx, my);
        drawTabs(g, mx, my);
        drawPreview(g, partial);
        drawContent(g, mx, my);
        strokeButton(g, px + 12, py + panelH - 28, 120, 20, tr("reset_all"), mx, my);
        strokeButton(g, px + panelW - 132, py + panelH - 28, 120, 20, tr("done"), mx, my);
        super.render(g, mx, my, partial);
        g.pose().popPose();
    }

    /** 页签栏（y+28..48）：等宽 5 页签，文字居中；活动页签微亮底 + 底部 2px ACCENT 指示条。 */
    private void drawTabs(GuiGraphics g, int mx, int my) {
        int tw = (panelW - 24) / TAB_KEYS.length;
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int tx = px + 12 + i * tw;
            boolean active = i == currentTab;
            boolean hover = rowHit(mx, my, tx, py + 28, tw, 20);
            if (active) g.fill(tx, py + 28, tx + tw, py + 48, 0x1A35D8A0);
            else if (hover) g.fill(tx, py + 28, tx + tw, py + 48, HOVER);
            String label = tr(TAB_KEYS[i]);
            text(g, label, tx + (tw - font.width(label)) / 2, py + 34, active ? ACCENT : hover ? TEXT : DIM);
            if (active) g.fill(tx, py + 46, tx + tw, py + 48, ACCENT);
        }
    }

    /**
     * 预览区（y+52..152）：外框 + 左 60% 迷你地图（独立 transform，不污染会话状态）+
     * 右 40% 九语义色样例 + 底部当前读数（不透明度 + 滤镜预设）。
     */
    private void drawPreview(GuiGraphics g, float partial) {
        int bx = px + 12, by = py + 52, bw = panelW - 24, bh = 100;
        box(g, bx, by, bw, bh, STROKE_DIM);
        int mapW = (bw - 4) * 60 / 100, mapH = bh - 16; // 左 60% 迷你地图，底部留一行读数
        int vx = bx + 2, vy = by + 2;
        // 每帧把滤镜预设同步到预览色板（内部 dirty 检查，值不变零开销）。
        previewPalette.setStyle(FactoryMapPalette.styleFromId(MapConfig.FILTER_STYLE.get()));
        g.fill(vx, vy, vx + mapW, vy + mapH, 0xFF09151B);
        if (minecraft.level != null) {
            // 独立 transform：绕开 session.transform（它会钳制/改写 session 的缩放与中心）。
            double pcx = minecraft.player != null ? minecraft.player.getX() : 0.0;
            double pcz = minecraft.player != null ? minecraft.player.getZ() : 0.0;
            var t = new MapCoordinateTransform(pcx, pcz, 1.0, vx, vy, mapW, mapH);
            g.enableScissor(vx, vy, vx + mapW, vy + mapH);
            previewTiles.draw(g, minecraft, t, previewSession);
            if (minecraft.player != null) {
                // v2.5.7 动效优化：与实际渲染路径同源喂 partialTick 插值坐标，亚像素绘制，
                // 保证"所见即所得"——设置页调的就是实际渲染路径（v2.5.7 图标换导航箭头）。
                PlayerMarkerFX fx = PlayerMarkerFX.INSTANCE;
                var ipos = minecraft.player.getPosition(partial);
                fx.update(ipos.x, ipos.z, minecraft.player.getYRot());
                float baseX = (float) t.worldToScreenX(ipos.x), baseY = (float) t.worldToScreenY(ipos.z);
                for (var s : fx.trail()) {
                    player(g, vx + mapW / 2 + (float) t.worldToScreenX(s.x()) - baseX,
                            vy + mapH / 2 + (float) t.worldToScreenY(s.z()) - baseY,
                            s.yaw(), s.alpha(), fx.stretch(), s.scale());
                }
                player(g, vx + mapW / 2, vy + mapH / 2, fx.smoothYaw(), 1f, fx.stretch(), fx.drawScale(Util.getMillis()));
            }
            g.disableScissor();
        }
        drawSwatches(g, vx + mapW + 4, vy, bw - 4 - mapW - 4, mapH);
        // 底部读数：不透明度 + 滤镜预设（样式名用语言键 filter_<id>，非法配置值回退 none 展示）。
        String styleId = FactoryMapPalette.styleFromId(MapConfig.FILTER_STYLE.get()).name().toLowerCase(Locale.ROOT);
        String readout = tr("opacity") + " " + f2(MapConfig.OPACITY.get())
                + " · " + tr("filter") + " " + tr("filter_" + styleId);
        text(g, readout, bx + 4, by + bh - 11, DIM);
    }

    /** 右 40%：9 个语义色样例（2 列 × 5 行）；对应图层关闭的语义画固定深色底并加角上小斜杠。 */
    private void drawSwatches(GuiGraphics g, int x, int y, int w, int h) {
        MapSemantics[] semantics = MapSemantics.values();
        int colW = w / 2, rowH = h / 5;
        for (int i = 0; i < semantics.length; i++) {
            MapSemantics s = semantics[i];
            int sx = x + (i / 5) * colW, sy = y + (i % 5) * rowH;
            boolean on = semanticEnabled(s);
            g.fill(sx, sy, sx + 16, sy + 16, on ? previewPalette.legendColor(s) : 0xFF0D141A);
            box(g, sx, sy, 16, 16, STROKE_DIM);
            if (!on) { // 关断标识：左上角红色小斜杠
                for (int k = 0; k < 6; k++) g.fill(sx + 2 + k, sy + 2 + k, sx + 3 + k, sy + 3 + k, 0xFFE45E62);
            }
            text(g, tr("sem_" + s.name().toLowerCase(Locale.ROOT)), sx + 19, sy + 4, on ? TEXT : DIM);
        }
    }

    /** 语义 → 图层开关映射（VOID/BLOCKED 无开关恒开；EDGE 跟随地面图层）。 */
    private static boolean semanticEnabled(MapSemantics s) {
        return switch (s) {
            case WALL -> MapConfig.SHOW_WALLS.get();
            case FLOOR, EDGE -> MapConfig.SHOW_FLOOR.get();
            case STAIR -> MapConfig.SHOW_STAIRS.get();
            case DOOR -> MapConfig.SHOW_DOORS.get();
            case WATER -> MapConfig.SHOW_WATER.get();
            case DECOR -> MapConfig.SHOW_DECOR.get();
            default -> true;
        };
    }

    /** 当前页签的滑条几何（绘制与命中共用同一份，保证逐像素对齐）。 */
    private List<SliderRow> sliders() {
        int cx = px + 12, cw = panelW - 24, cy = py + 160;
        return switch (currentTab) {
            // 楼层页（v2.5.2：手动楼层已删，仅保留自动层防抖灵敏度与扫描范围）：
            // cy+4 说明行 + span(cy+40) + radius(cy+60)。
            case 0 -> List.of(new SliderRow(0, cx, cy + 40, cw, SPAN_SLIDER),
                    new SliderRow(1, cx, cy + 60, cw, RADIUS_SLIDER));
            case 2 -> List.of(new SliderRow(2, cx, cy + 52, cw, OPACITY_SLIDER));
            // 行为页 6 行（跟随/小地图开关/小地图缩放/尺寸/缩放范围×2）压进 108px 内容区：
            // 0,16 两个开关行 + 34,52,70,88 四个滑条行（末行 88+18=106 ≤ 108，
            // v2.5.2 重排：不再与底部按钮行重叠，小地图尺寸滑条完整可见可点）。
            case 3 -> List.of(new SliderRow(3, cx, cy + 34, cw, MINIMAP_ZOOM_SLIDER),
                    new SliderRow(4, cx, cy + 52, cw, MINIMAP_SIZE_SLIDER),
                    new SliderRow(6, cx, cy + 70, cw, ZOOM_MIN_SLIDER),
                    new SliderRow(7, cx, cy + 88, cw, ZOOM_MAX_SLIDER));
            // 标点页（v2.6.0 新增玩家图标大小滑条，预览区导航箭头实时缩放）：
            // 风格块 cy / 距离 cy+30 / 倒计时 cy+50（开关行，见 drawContent）+
            // 玩家图标大小滑条 cy+70（18 行高，70+18=88 ≤ 108 内容区不溢出）。
            case 4 -> List.of(new SliderRow(5, cx, cy + 70, cw, PLAYER_SIZE_SLIDER));
            default -> List.of();
        };
    }

    /** 内容区（y+160..H-32，固定不滚动）：按 currentTab 渲染对应控件 + 该页签的全部滑条。 */
    private void drawContent(GuiGraphics g, int mx, int my) {
        int cx = px + 12, cw = panelW - 24, cy = py + 160;
        switch (currentTab) {
            case 0 -> { // 楼层（v2.5.2：恒定自动跟随玩家，反馈⑤删除手动层）：说明 + 层高跨度/扫描半径滑条
                text(g, tr("auto_floor_note"), cx, cy + 4, DIM);
            }
            case 1 -> { // 图层：8 开关两列 × 4 行（内容区高度有限，单列 8 行放不下）
                int colW = (cw - 8) / 2;
                for (int i = 0; i < LAYER_TOGGLES.length; i++) {
                    LayerToggle lt = LAYER_TOGGLES[i];
                    toggleRow(g, cx + (i / 4) * (colW + 8), cy + (i % 4) * 20, colW,
                            tr(lt.key()), lt.value().get(), mx, my, ACCENT);
                }
            }
            case 2 -> { // 视觉：6 滤镜预设块（2 行 × 3 列，id 由 Style 枚举生成）+
                // 不透明度滑条（cy+52，见 sliders()）+ 网格开关与恢复视觉默认（cy+76 一行）+
                // 开图清屏开关（v2.5.1 新增，cy+92，内容区末行）
                FactoryMapPalette.Style[] styles = FactoryMapPalette.Style.values();
                FactoryMapPalette.Style current = FactoryMapPalette.styleFromId(MapConfig.FILTER_STYLE.get());
                int bw = (cw - 16) / 3;
                for (int i = 0; i < styles.length; i++) {
                    String id = styles[i].name().toLowerCase(Locale.ROOT);
                    styleBlock(g, cx + (i % 3) * (bw + 8), cy + (i / 3) * 26, bw, 22,
                            tr("filter_" + id), styles[i] == current, mx, my);
                }
                toggleRow(g, cx, cy + 76, 200, tr("grid"), MapConfig.GRID.get(), mx, my, ACCENT);
                strokeButton(g, cx + cw - 140, cy + 76, 140, 16, tr("reset_vision"), mx, my);
                toggleRow(g, cx, cy + 92, cw, tr("clean_on_open"), MapConfig.CLEAN_ON_OPEN.get(), mx, my, ACCENT);
            }
            case 3 -> { // 行为：跟随 + 小地图开关（小地图缩放/尺寸与缩放范围滑条见 sliders()，
                // v2.5.2 重排：小地图尺寸滑条在 GUI 内完整可见，反馈②）
                toggleRow(g, cx, cy, cw, tr("follow"), MapConfig.FOLLOW_PLAYER.get(), mx, my, ACCENT);
                toggleRow(g, cx, cy + 16, cw, tr("minimap"), MapConfig.MINIMAP_ENABLED.get(), mx, my, ACCENT);
            }
            case 4 -> { // 标点：风格两选项块 + 距离/倒计时开关 + 玩家图标大小滑条（v2.6.0，
                // 见 sliders()：预览区导航箭头实时缩放，拖动即所见即所得）
                boolean tactical = STYLE_TACTICAL.equals(MapConfig.MARKER_STYLE.get());
                styleBlock(g, cx, cy, 60, 22, tr("style_tactical"), tactical, mx, my);
                styleBlock(g, cx + 68, cy, 60, 22, tr("style_minimal"), !tactical, mx, my);
                toggleRow(g, cx, cy + 30, cw, tr("show_distances"), MapConfig.SHOW_DISTANCES.get(), mx, my, ACCENT);
                toggleRow(g, cx, cy + 50, cw, tr("show_countdown"), MapConfig.SHOW_COUNTDOWN.get(), mx, my, ACCENT);
            }
            default -> { }
        }
        for (SliderRow r : sliders()) drawSlider(g, r.x(), r.y(), r.w(), SLIDER_H, r.helper(), mx, my);
    }

    /** 自绘滑条行：label（TEXT）+ 当前值（ACCENT，轨道左侧右对齐）+ 60px 轨道 + 6×10 手柄。 */
    private void drawSlider(GuiGraphics g, int x, int y, int w, int h, SliderHelper helper, int mx, int my) {
        text(g, tr(helper.key()), x, y + 5, TEXT);
        int tx = x + w - TRACK_W - 2;
        String value = helper.format().apply(helper.get().getAsDouble());
        text(g, value, tx - 8 - font.width(value), y + 5, ACCENT);
        boolean hover = rowHit(mx, my, tx - 12, y, TRACK_W + 24, h);
        g.fill(tx, y + (h - 6) / 2, tx + TRACK_W, y + (h - 6) / 2 + 6, hover ? 0xFF44546A : STROKE_DIM);
        int handleX = tx + (int) Math.round(helper.fraction() * (TRACK_W - 6));
        g.fill(handleX, y + (h - 10) / 2, handleX + 6, y + (h - 10) / 2 + 10, ACCENT);
    }

    /** 标点风格选项块：活动 = ACCENT 边框 + 微亮底；hover 增亮；文字居中。 */
    private void styleBlock(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, int mx, int my) {
        if (rowHit(mx, my, x, y, w, h)) g.fill(x, y, x + w, y + h, HOVER);
        else if (active) g.fill(x, y, x + w, y + h, 0x1435D8A0);
        box(g, x, y, w, h, active ? ACCENT : STROKE_DIM);
        text(g, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, active ? ACCENT : TEXT);
    }

    // ==================== 交互 ====================

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) {
            double lx = x / uiScale;
            int mx = (int) lx, my = (int) (y / uiScale);
            int cx = px + 12, cw = panelW - 24, cy = py + 160;
            if (rowHit(mx, my, px + panelW - 34, py + 4, 30, 16)) { onClose(); return true; } // 右上 X
            int tw = (panelW - 24) / TAB_KEYS.length; // 页签
            if (rowHit(mx, my, px + 12, py + 28, tw * TAB_KEYS.length, 20)) {
                currentTab = Mth.clamp((mx - (px + 12)) / tw, 0, TAB_KEYS.length - 1);
                return true;
            }
            if (rowHit(mx, my, px + 12, py + panelH - 28, 120, 20)) { MapConfig.resetToDefaults(); return true; }
            if (rowHit(mx, my, px + panelW - 132, py + panelH - 28, 120, 20)) { onClose(); return true; }
            for (SliderRow r : sliders()) { // 滑条起点：点击即跳到点击位置并进入拖动
                if (r.hit(mx, my)) { draggingSlider = r.id(); applySlider(r, lx); return true; }
            }
            switch (currentTab) {
                case 0 -> {
                    // v2.5.2：手动层控件已删（反馈⑤），本页仅剩滑条（sliders() 已处理）。
                }
                case 1 -> {
                    int colW = (cw - 8) / 2;
                    for (int i = 0; i < LAYER_TOGGLES.length; i++) {
                        if (rowHit(mx, my, cx + (i / 4) * (colW + 8), cy + (i % 4) * 20, colW, 16)) {
                            MapConfig.set(LAYER_TOGGLES[i].value(), !LAYER_TOGGLES[i].value().get());
                            return true;
                        }
                    }
                }
                case 2 -> {
                    FactoryMapPalette.Style[] styles = FactoryMapPalette.Style.values();
                    int bw = (cw - 16) / 3;
                    for (int i = 0; i < styles.length; i++) {
                        if (rowHit(mx, my, cx + (i % 3) * (bw + 8), cy + (i / 3) * 26, bw, 22)) {
                            MapConfig.set(MapConfig.FILTER_STYLE, styles[i].name().toLowerCase(Locale.ROOT));
                            return true;
                        }
                    }
                    if (rowHit(mx, my, cx, cy + 76, 200, 16)) {
                        MapConfig.set(MapConfig.GRID, !MapConfig.GRID.get()); return true;
                    }
                    if (rowHit(mx, my, cx + cw - 140, cy + 76, 140, 16)) { resetVisionDefaults(); return true; }
                    if (rowHit(mx, my, cx, cy + 92, cw, 16)) {
                        MapConfig.set(MapConfig.CLEAN_ON_OPEN, !MapConfig.CLEAN_ON_OPEN.get()); return true;
                    }
                }
                case 3 -> {
                    if (rowHit(mx, my, cx, cy, cw, 16)) {
                        MapConfig.set(MapConfig.FOLLOW_PLAYER, !MapConfig.FOLLOW_PLAYER.get()); return true;
                    }
                    if (rowHit(mx, my, cx, cy + 16, cw, 16)) {
                        MapConfig.set(MapConfig.MINIMAP_ENABLED, !MapConfig.MINIMAP_ENABLED.get()); return true;
                    }
                }
                case 4 -> {
                    if (rowHit(mx, my, cx, cy, 60, 22)) { MapConfig.set(MapConfig.MARKER_STYLE, STYLE_TACTICAL); return true; }
                    if (rowHit(mx, my, cx + 68, cy, 60, 22)) { MapConfig.set(MapConfig.MARKER_STYLE, STYLE_MINIMAL); return true; }
                    if (rowHit(mx, my, cx, cy + 30, cw, 16)) {
                        MapConfig.set(MapConfig.SHOW_DISTANCES, !MapConfig.SHOW_DISTANCES.get()); return true;
                    }
                    if (rowHit(mx, my, cx, cy + 50, cw, 16)) {
                        MapConfig.set(MapConfig.SHOW_COUNTDOWN, !MapConfig.SHOW_COUNTDOWN.get()); return true;
                    }
                }
                default -> { }
            }
        }
        return super.mouseClicked(x / uiScale, y / uiScale, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && draggingSlider >= 0) {
            double lx = x / uiScale;
            for (SliderRow r : sliders()) {
                if (r.id() == draggingSlider) { applySlider(r, lx); break; }
            }
            return true;
        }
        return super.mouseDragged(x / uiScale, y / uiScale, button, dx / uiScale, dy / uiScale);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && draggingSlider >= 0) { draggingSlider = -1; return true; }
        return super.mouseReleased(x / uiScale, y / uiScale, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    /** 拖动/点击滑条：按鼠标 X 相对轨道的比例换算步进值并经 helper.set 写回配置。 */
    private void applySlider(SliderRow r, double mouseX) {
        double fraction = Mth.clamp((mouseX - r.trackX()) / TRACK_W, 0.0, 1.0);
        r.helper().set().accept(r.helper().valueAt(fraction));
    }

    /** 恢复视觉默认：不透明度 / 网格 / 滤镜预设（回彩色原色 none）。 */
    private static void resetVisionDefaults() {
        MapConfig.set(MapConfig.OPACITY, MapConfig.OPACITY.getDefault());
        MapConfig.set(MapConfig.GRID, MapConfig.GRID.getDefault());
        MapConfig.set(MapConfig.FILTER_STYLE, FactoryMapPalette.Style.NONE.name().toLowerCase(Locale.ROOT));
    }

    @Override public boolean isPauseScreen() { return false; }
    // 与地图一致：自己掌控底幕，vanilla 的菜单模糊会糊掉刚画好的面板与预览。
    @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {}

    @Override public void removed() {
        previewTiles.close();
        MapConfig.flush(); // 关屏兜底：防抖静默期内未到 500ms 的改动立即落盘
        super.removed();
    }
}
