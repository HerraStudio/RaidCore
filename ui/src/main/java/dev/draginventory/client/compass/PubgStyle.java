package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 绝地求生皮肤（按用户参考图 759x91 逐项还原）：
 * <ul>
 *   <li>垂直层次（参考图实测）：顶部白色下指三角（屏幕中心固定）→
 *       半透明深色条带 → 刻度带（从条带上沿垂下）→ 文字带（最底）</li>
 *   <li>刻度分级（参考图实测）：字母位（N/E/S/W/NE/SE…）= 3px 粗 11px 高白刻度，
 *       N 的刻度为金色；数字位 = 1px 细 7px 高暗刻度</li>
 *   <li>文字分级：N/E/S/W 大号加粗白字（N 为 PUBG 标志性金色），
 *       NE/SE 中号白字，数字小号银灰（实测 #909796）</li>
 *   <li>无当前度数大读数、无中心数值框（参考图中心只有那枚 ▼）</li>
 *   <li>两端硬截止（原作刻度直接被裁掉，无渐隐）</li>
 * </ul>
 */
final class PubgStyle extends CompassStyle {

    /** PUBG 标志性 N 金色（参考图实测 #A89D41 经曝光还原）。 */
    private static final int GOLD = 0xF0C850;

    // ==================== 布局 ====================
    // ▼三角 y0-10 / 条带 y13-47 / 刻度自 y15 垂下 / 文字底基线 y44 / 标点 y52。

    @Override
    public String id() {
        return "pubg";
    }

    @Override
    public String nameKey() {
        return "draginventory.compass.style.pubg";
    }

    @Override
    public float edgeFadeSpan() {
        return 0f;
    }

    @Override
    public float labelBaselineY() {
        return 44f;
    }

    @Override
    public float tickTopY() {
        return 15f;
    }

    @Override
    public float tickHeight(TickKind kind) {
        return 11f;
    }

    @Override
    public float markerY() {
        return 52f;
    }

    @Override
    public float widgetHeight() {
        // 标点文字行最低到 y+64。
        return 66f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackground(CompassStyleContext ctx, GuiGraphics g) {
        // 参考图：半透明深色圆角条带，覆盖刻度带+文字带（y13-47），硬截止。
        float x = ctx.originX, w = ctx.width;
        float y = ctx.originY + 13, h = 34;
        CompassPaint.roundedRect(g, x, y, w, h, ctx.palette.background(),
                0.42f * ctx.alpha, 3);
    }

    @Override
    public void drawTick(CompassStyleContext ctx, GuiGraphics g, float x, TickKind kind,
                         int degrees, float alpha) {
        // 参考图刻度只有 15 度档（字母位粗 / 数字位细），无 5 度次级刻度。
        if (kind == TickKind.MINOR) return;
        float y = ctx.originY + tickTopY() + ctx.skewAt(x);
        if (degrees % 45 == 0) {
            // 字母位：粗刻度。N（0 度）金色，其余白（参考图 x116 金刻度 / x381 等白粗刻度）。
            boolean isNorth = degrees == 0;
            boolean nearest = Math.abs(x - ctx.centerX) < 3f && ctx.cardinalGlow > 0.03f;
            int rgb = isNorth ? GOLD : ctx.palette.tick();
            if (nearest && !isNorth) {
                rgb = CompassStyleContext.blend(rgb, ctx.palette.accent(), ctx.cardinalGlow);
            }
            CompassStyleContext.vline(g, x, y, 11f, 2.6f, rgb,
                    alpha * (isNorth ? 1f : 0.9f));
        } else {
            // 数字位：细暗刻度（参考图 1px 高 8px、亮度约为粗刻度一半）。
            CompassStyleContext.vline(g, x, y, 7f, 1f, ctx.palette.tick(),
                    alpha * 0.45f);
        }
    }

    @Override
    public void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                             float x, String label, boolean nearest, float alpha) {
        // N/E/S/W 大号加粗；N 金色（PUBG 招牌），其余白（实测 #B7BEBD）。
        boolean isNorth = "N".equals(label) || "北".equals(label);
        float glow = nearest ? ctx.cardinalGlow : 0;
        int rgb = isNorth ? GOLD : ctx.palette.text();
        if (glow > 0.03f && !isNorth) {
            rgb = CompassStyleContext.blend(rgb, ctx.palette.accent(), glow * 0.6f);
        }
        float scale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * 1.28f * (1f + glow * 0.08f));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, Component.literal(label), x, y - 9.75f * scale,
                scale, rgb, Math.min(1f, alpha * (1f + glow * 0.12f)), true);
    }

    @Override
    public void drawIntercardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                                  float x, String label, float alpha) {
        // NE/SE：中号白字（参考图与数字同带、略亮）。
        float scale = 1.02f;
        CompassPaint.centeredScaled(font, g, Component.literal(label), x,
                ctx.originY + labelBaselineY() + ctx.skewAt(x) - 9.75f * scale, scale,
                ctx.palette.text(), alpha * 0.9f, true);
    }

    @Override
    public void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                           float x, int degrees, float alpha) {
        // 数字在刻度正下方、条带最底（参考图 y58-70 文字带）。
        // v1.5.3：与相邻方位字母碰撞时跳过（基数字母 1.28 倍 + 发光放大）。
        float cardinalScale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * 1.28f * 1.08f);
        if (numberOverlapsLetter(ctx, font, x, degrees, String.valueOf(degrees),
                0.85f, cardinalScale, 1.02f)) {
            return;
        }
        var text = Component.literal(String.valueOf(degrees))
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float scale = 0.85f;
        CompassPaint.centeredScaled(font, g, text, x,
                ctx.originY + labelBaselineY() + ctx.skewAt(x) - 9.75f * scale, scale,
                ctx.palette.dim(), alpha, true);
    }

    @Override
    public void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g) {
        // 参考图中心元素仅一枚：条带上方悬浮的白色下指三角（y22-34，约 11px 宽 12px 高）。
        float cx = ctx.centerX;
        float glow = ctx.cardinalGlow;
        int rgb = glow > 0.03f
                ? CompassStyleContext.blend(0xFFFFFF, ctx.palette.accent(), glow * 0.4f)
                : 0xFFFFFF;
        CompassPaint.triangleDown(g, cx, ctx.originY + 0.5f, 5.2f, 10, rgb,
                ctx.alpha * (0.92f + glow * 0.08f));
    }

    @Override
    public void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                           CompassMark mark, float x, float alpha, @Nullable String text) {
        float pulse = ctx.markerPulse
                ? 1f + 0.12f * (float) Math.sin((ctx.now - mark.createdAtMillis()) / 300.0 * Math.PI * 2)
                : 1f;
        float y = ctx.originY + markerY();
        float half = 2.5f * pulse;
        // PUBG 风：扁平小形状，无光晕层（原作的队友标记就是小而扁平的）。
        markerShape(g, mark, x, y, half, alpha);
        if (text != null && alpha > 0.35f) {
            var label = Component.literal(text)
                    .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
            CompassPaint.centeredScaled(font, g, label, x, y + 5f, 0.7f, mark.color(), alpha * 0.85f, false);
        }
    }
}
