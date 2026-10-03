package dev.draginventory.client.compass;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 方位条皮肤（风格）。控件负责迭代刻度/标签/标点并计算位置、透明度与动画系数，
 * 皮肤只决定每一类元素“长什么样”。所有皮肤共享 {@link CompassStyleContext} 工具。
 *
 * <p>当前皮肤库为五套游戏风格还原：delta（三角洲行动）/ pubg（绝地求生）/
 * apex（Apex 英雄）/ battlefield（战地）/ warzone（使命召唤）。</p>
 *
 * <p>新增皮肤：继承本类并注册到 {@link #ALL} 即可被指令 / 设置界面识别。</p>
 */
public abstract class CompassStyle {
    private static final Map<String, CompassStyle> ALL = new LinkedHashMap<>();

    static {
        register(new DeltaStyle());
        register(new PubgStyle());
        register(new ApexStyle());
        register(new BattlefieldStyle());
        register(new WarzoneStyle());
    }

    private static void register(CompassStyle style) {
        ALL.put(style.id(), style);
    }

    public static CompassStyle byId(String id) {
        CompassStyle style = ALL.get(id);
        return style != null ? style : ALL.get("delta");
    }

    /** 精确查找（不回退），指令校验未知 id 用。 */
    public static CompassStyle byIdOrNull(String id) {
        return ALL.get(id);
    }

    /** 逗号分隔的合法 id 列表（指令错误提示用）。 */
    public static String idList() {
        return String.join(", ", ALL.keySet());
    }

    public static Collection<CompassStyle> all() {
        return ALL.values();
    }

    protected CompassStyle() {}

    /** 皮肤 id（配置文件 / 指令使用）。 */
    public abstract String id();

    /** 显示名（lang key）。 */
    public abstract String nameKey();

    // ==================== 布局度量（未缩放局部坐标） ====================

    /** 标签行（方位字 + 数字）基线 y。 */
    public float labelBaselineY() {
        return 23f;
    }

    /** 刻度顶部 y（刻度向下生长）。 */
    public float tickTopY() {
        return 28f;
    }

    /** 各级刻度高度。 */
    public float tickHeight(TickKind kind) {
        return switch (kind) {
            case CARDINAL -> 9f;
            case MAJOR -> 6.5f;
            case MINOR -> 4f;
        };
    }

    /** 标点行中心 y（刻度基线下方）。 */
    public float markerY() {
        return 43f;
    }

    /** 控件总高（含标点距离文字）。 */
    public float widgetHeight() {
        return 56f;
    }

    /**
     * 元素边缘渐隐跨度（占半宽比例，0.26 = 最外 13% 全宽渐隐）。
     * 大多数游戏（三角洲/Apex/COD/战地）在两端 10-15% 内线性渐隐；
     * PUBG 原作是硬截止（刻度直接被裁掉），覆写为 0。
     */
    public float edgeFadeSpan() {
        return 0.26f;
    }

    // ==================== 视觉钩子 ====================

    /** 半透明背景（整条）。 */
    public abstract void drawBackground(CompassStyleContext ctx, GuiGraphics g);

    /**
     * 单根刻度线。x 为局部坐标，alpha 已含边缘渐隐 / 入场 / 全局透明度。
     * degrees 为该刻度对应的罗盘角度（0-359 已归一），
     * 皮肤据此区分“字母位刻度”（45 倍数）与“数字位刻度”：
     * Apex 只在数字位画细刻度（字母本身占据全高），PUBG 在字母位画粗刻度。
     */
    public abstract void drawTick(CompassStyleContext ctx, GuiGraphics g, float x, TickKind kind,
                                   int degrees, float alpha);

    /** 基数方位字（北/东/南/西）。nearest = 当前吸附目标。 */
    public abstract void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                                      float x, String label, boolean nearest, float alpha);

    /** 次方位字（东北等）。 */
    public void drawIntercardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                                  float x, String label, float alpha) {
        CompassStyleContext.text(font, g, Component.literal(label), x - font.width(label) / 2f,
                ctx.originY + labelBaselineY(), ctx.palette.text(), alpha * 0.72f, false);
    }

    /** 角度数字。 */
    public abstract void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                                     float x, int degrees, float alpha);

    /**
     * v1.5.3：角度数字与相邻大号方位字母的横向碰撞检测。
     *
     * <p>所有皮肤的方位字与角度数字共用 {@link #labelBaselineY} 底基线。视野范围调大
     * （每度像素变少）、密度自适应加密数字、或中文双字标签（“东北”）时，45° 倍数位的
     * 大号字母会横向压住紧邻的角度数字——渲染顺序数字在前、字母在后，表现为
     * “次方位字体遮挡度数”。本方法按两侧最近 45° 倍数字母的实测宽度做碰撞判定；
     * 调用方（各皮肤 {@link #drawNumber}）在重叠时跳过该数字（该位置细刻度保留，
     * 仍有方位指示）。默认配置（240px 条带 / 120° 视野 / 15° 步长）下间距充裕，
     * 不会触发跳过，行为与旧版完全一致。</p>
     *
     * @param numberText 数字实际文本（如 "30" / "030"，宽度按其估算）
     * @param numberScale 数字缩放
     * @param cardinalLetterScale 基数字母完整缩放（含 CARDINAL_SCALE 与发光放大余量）
     * @param interLetterScale 次方位字母完整缩放
     */
    protected boolean numberOverlapsLetter(CompassStyleContext ctx, Font font, float x,
                                           int degrees, String numberText, float numberScale,
                                           float cardinalLetterScale, float interLetterScale) {
        float numberHalf = font.width(numberText) * numberScale / 2f + 1.5f;
        int lower = Math.floorDiv(degrees, 45) * 45;
        for (int neighbor = lower; neighbor <= lower + 45; neighbor += 45) {
            int wrapped = Math.floorMod(neighbor, 360);
            boolean cardinal = wrapped % 90 == 0;
            if (cardinal ? !CompassConfig.SHOW_CARDINALS.get()
                         : !CompassConfig.SHOW_INTERCARDINALS.get()) {
                continue; // 该位字母未开启显示，不参与碰撞
            }
            float letterX = ctx.degreesToX(wrapped);
            // 字母在条带可视范围外（与刻度相同的 8px 余量）或已边缘淡出 → 不参与碰撞
            if (letterX < ctx.originX - 8f || letterX > ctx.originX + ctx.width + 8f) continue;
            if (ctx.edgeFade(letterX) < 0.15f) continue;
            String label = cardinal
                    ? CompassWidget.cardinalName(wrapped)
                    : CompassWidget.intercardinalName(wrapped);
            float letterScale = cardinal ? cardinalLetterScale : interLetterScale;
            float letterHalf = font.width(label) * letterScale / 2f + 1.5f;
            if (Math.abs(x - letterX) < letterHalf + numberHalf) {
                return true;
            }
        }
        return false;
    }

    /** 中心指示（caret + 当前角度数字）。 */
    public abstract void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g);

    /** 标点。text 为标点下方文字行（标签/距离，可为 null 不显示）。 */
    public abstract void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                                    CompassMark mark, float x, float alpha, @Nullable String text);

    /** 前景装饰（风格化元素，绘制在所有元素之上）。 */
    public void drawForeground(CompassStyleContext ctx, GuiGraphics g) {
    }

    /**
     * 标点核心图标：<b>与 3D 战术标点同款</b>（用户要求方位条下的标记和实际标点一致）：
     * ENEMY = 红色感叹号、LOCATION = 白色菱形（45° 方块）、ITEM = 物品本体贴图
     * （icon 为空时回退空心方块）、DEATH = X 十字、EXTERNAL = 菱形。
     * half 为常规图标的目标半宽，皮肤保留各自的光晕/脉冲气质。
     */
    protected static void markerShape(GuiGraphics g, CompassMark mark, float cx, float cy,
                                      float half, float alpha) {
        switch (mark.kind()) {
            case ENEMY -> CompassPaint.exclamation(g, cx, cy, Math.max(0.75f, half / 2.8f),
                    mark.color(), alpha);
            case LOCATION -> CompassPaint.locationDiamond(g, cx, cy, Math.max(0.8f, half / 2.9f),
                    mark.color(), alpha);
            case ITEM -> {
                if (mark.icon() != null && !mark.icon().isEmpty()) {
                    CompassPaint.itemIcon(g, mark.icon(), cx, cy, half * 3.4f, alpha);
                } else {
                    CompassPaint.squareOutline(g, cx, cy, half * 0.95f, 1f, mark.color(), alpha);
                }
            }
            case DEATH -> CompassPaint.crossX(g, cx, cy, half * 1.1f, mark.color(), alpha);
            default -> CompassPaint.diamond(g, cx, cy, half, mark.color(), alpha);
        }
    }

    /** 刻度种类。 */
    public enum TickKind { MINOR, MAJOR, CARDINAL }
}
