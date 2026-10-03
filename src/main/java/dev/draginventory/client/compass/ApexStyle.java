package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Apex 英雄皮肤（按用户参考图 937x47 逐项还原）：
 * <ul>
 *   <li>悬浮式：无实体条带、无中心指示、无读数框 —— 参考图里只有
 *       悬在场景上的文字与刻度（底层仅一层极轻压暗保证 MC 亮天空下可读）</li>
 *   <li>八个方位点（N/NE/E/SE/S/SW/W/NW）全部是大号字母，
 *       与数字共用底基线（参考图 y=40 线）；字母顶 = 刻度顶</li>
 *   <li>角度数字小号、更暗，正上方一根细刻度（约 10px、2px 宽），
 *       字母位不画刻度 —— 字母本身就是那一档的“刻度”</li>
 *   <li>暖白 #E8E8E0（参考实测 #F8F8F0/#B0B0B0 的折中），无金色 N</li>
 *   <li>两端 Alpha 渐隐（参考图右侧 195 之后可见明显淡出）</li>
 * </ul>
 */
final class ApexStyle extends CompassStyle {

    /** 大字母相对默认字号的缩放（参考图字母高约为数字高的 2.4 倍）。 */
    private static final float LETTER_SCALE = 1.85f;
    /** 数字缩放。 */
    private static final float NUMBER_SCALE = 0.80f;
    /** 数字位细刻度：顶 y（与字母顶对齐）与高。 */
    private static final float TICK_TOP = 8f;
    private static final float TICK_HEIGHT = 10f;

    @Override
    public String id() {
        return "apex";
    }

    @Override
    public String nameKey() {
        return "draginventory.compass.style.apex";
    }

    // ==================== 布局 ====================
    // 内容带 y8-38：刻度/字母顶 y8，全部文字底基线 y34（参考图 y15-40 的等比）。

    @Override
    public float labelBaselineY() {
        return 34f;
    }

    @Override
    public float tickTopY() {
        return TICK_TOP;
    }

    @Override
    public float tickHeight(TickKind kind) {
        return TICK_HEIGHT;
    }

    @Override
    public float markerY() {
        return 44f;
    }

    @Override
    public float widgetHeight() {
        // 无条带下方读数；标点文字行最低到 y+56。
        return 58f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackground(CompassStyleContext ctx, GuiGraphics g) {
        // 参考图无条带；仅一层极轻整幅压暗（原作靠场景动态模糊压背景），
        // 保证 MC 亮色天空/雪地时白字仍可读。
        float x = ctx.originX, w = ctx.width;
        float y = ctx.originY + 4, h = 36;
        g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h),
                CompassStyleContext.rgba(ctx.palette.background(), 0.13f * ctx.alpha));
    }

    @Override
    public void drawTick(CompassStyleContext ctx, GuiGraphics g, float x, TickKind kind,
                         int degrees, float alpha) {
        // 参考图无 5 度次级刻度：只有数字位的细刻度，字母位（45 倍数）由大字母本身占位。
        if (kind == TickKind.MINOR || degrees % 45 == 0) return;
        float y = ctx.originY + TICK_TOP + ctx.skewAt(x);
        CompassStyleContext.vline(g, x, y, TICK_HEIGHT, 1.6f, ctx.palette.tick(),
                alpha * 0.80f);
    }

    @Override
    public void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                             float x, String label, boolean nearest, float alpha) {
        drawBigLetter(ctx, font, g, x, label, nearest, alpha);
    }

    @Override
    public void drawIntercardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                                  float x, String label, float alpha) {
        // 参考图 NE/SE 与 N/E/S 同高（y15-40 连续亮区），仅略暗。
        drawBigLetter(ctx, font, g, x, label, false, alpha * 0.88f);
    }

    /** 大号方位字母：底基线对齐 labelBaselineY，接近中心时轻微提亮放大。 */
    private void drawBigLetter(CompassStyleContext ctx, Font font, GuiGraphics g,
                               float x, String label, boolean nearest, float alpha) {
        float glow = nearest ? ctx.cardinalGlow : 0;
        int rgb = CompassStyleContext.blend(ctx.palette.text(), ctx.palette.accent(), glow * 0.55f);
        float scale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * LETTER_SCALE * (1f + glow * 0.10f));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        // 底基线对齐：文字顶 = 基线 - 字高*缩放（平滑字体字高约 9.75）。
        float topY = y - 9.75f * scale;
        CompassPaint.centeredScaled(font, g, Component.literal(label), x, topY, scale, rgb,
                Math.min(1f, alpha * (1f + glow * 0.15f)), false);
    }

    @Override
    public void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                           float x, int degrees, float alpha) {
        // v1.5.3：本皮肤字母为 1.85 倍大字（各皮肤中最大），与数字共底基线，
        // 视野调大/数字加密/中文双字标签时字母会横向压住度数 —— 碰撞时跳过数字。
        float letterScale = (float) CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * LETTER_SCALE * 1.10f;
        if (numberOverlapsLetter(ctx, font, x, degrees, String.valueOf(degrees),
                NUMBER_SCALE, letterScale, letterScale)) {
            return;
        }
        var text = Component.literal(String.valueOf(degrees))
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        // 底基线与大字母对齐（参考图数字 y30-40、字母 y15-40）。
        float topY = y - 9.75f * NUMBER_SCALE;
        CompassPaint.centeredScaled(font, g, text, x, topY, NUMBER_SCALE, ctx.palette.dim(),
                alpha * 0.95f, false);
    }

    @Override
    public void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g) {
        // 参考图无中心指示器、无读数（原作 Apex 顶部罗盘就是纯滚动条带）。
    }

    @Override
    public void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                           CompassMark mark, float x, float alpha, @Nullable String text) {
        float pulse = ctx.markerPulse
                ? 1f + 0.14f * (float) Math.sin((ctx.now - mark.createdAtMillis()) / 270.0 * Math.PI * 2)
                : 1f;
        float y = ctx.originY + markerY();
        float half = 2.7f * pulse;
        // Apex ping 气质：外圈放大淡影 + 实际标点同款核心图标。
        markerShape(g, mark, x, y, half + 1.6f, alpha * 0.4f);
        markerShape(g, mark, x, y, half, alpha);
        if (text != null && alpha > 0.35f) {
            var label = Component.literal(text)
                    .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
            CompassPaint.centeredScaled(font, g, label, x, y + 5.5f, 0.72f, mark.color(), alpha * 0.9f, false);
        }
    }
}
