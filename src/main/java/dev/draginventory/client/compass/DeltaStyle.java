package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 三角洲行动皮肤（默认，按实机参考图逐项还原）：
 * <ul>
 *   <li>顶部一条贯穿全宽的细基线，刻度全部从基线向下垂挂：
 *       15 度位 2px 粗 10px 高，5 度位 1px 细 6px 高</li>
 *   <li>文字在刻度下方：八个方位点显示方位词（“北”为金色高亮，原作招牌），
 *       其余 15 度位显示三位补零度数（015 / 030 / …）</li>
 *   <li>中心是嵌在条带里的当前朝向大读数：深色底衬 + 左右方括号 [ NNN ]，
 *       字号约为标签 2.2 倍；读数正下方一枚下指三角</li>
 *   <li>两端在条带高度内渐隐</li>
 * </ul>
 */
final class DeltaStyle extends CompassStyle {

    /** 三角洲“北”金色（参考实测 #FFB800 系）。 */
    private static final int GOLD = 0xFFB300;

    // ==================== 布局 ====================
    // 条带 y6-40 / 基线 y8 / 刻度自 y9 垂下 / 文字底基线 y36 /
    // 中心大读数框 y3-43 / 读数下三角 y45-49 / 标点 y56。

    @Override
    public String id() {
        return "delta";
    }

    @Override
    public String nameKey() {
        return "draginventory.compass.style.delta";
    }

    @Override
    public float labelBaselineY() {
        return 36f;
    }

    @Override
    public float tickTopY() {
        return 9f;
    }

    @Override
    public float tickHeight(TickKind kind) {
        return 10f;
    }

    @Override
    public float markerY() {
        return 56f;
    }

    @Override
    public float widgetHeight() {
        // 标点文字行最低到 y+68，预留裁剪余量。
        return 70f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackground(CompassStyleContext ctx, GuiGraphics g) {
        float x = ctx.originX, y = ctx.originY + 6, w = ctx.width, h = 34;
        float a = 0.50f * ctx.alpha;
        // 主体：中间全强 + 两端 14% 在条带自身高度内渐隐。
        float fade = w * 0.14f;
        CompassPaint.gradientH(g, x, x + fade, y, h, ctx.palette.background(), 0f, a, 12);
        g.fill(Math.round(x + fade), Math.round(y), Math.round(x + w - fade), Math.round(y + h),
                CompassStyleContext.rgba(ctx.palette.background(), a));
        CompassPaint.gradientH(g, x + w - fade, x + w, y, h, ctx.palette.background(), a, 0f, 12);
        // 顶部基线（原作刻度的悬挂轨道）：全宽 1px 中灰线，避开渐隐区。
        float ex1 = x + fade * 0.5f, ex2 = x + w - fade * 0.5f;
        CompassStyleContext.hline(g, ex1, ex2, y + 2f, 1f, ctx.palette.tick(),
                0.55f * ctx.alpha);
    }

    @Override
    public void drawTick(CompassStyleContext ctx, GuiGraphics g, float x, TickKind kind,
                         int degrees, float alpha) {
        float y = ctx.originY + tickTopY() + ctx.skewAt(x);
        if (degrees % 15 == 0) {
            // 15 度位粗刻度（字母与数字同规格，原作如此）。
            boolean nearest = Math.abs(x - ctx.centerX) < 3f && ctx.cardinalGlow > 0.03f;
            int rgb = nearest
                    ? CompassStyleContext.blend(ctx.palette.tick(), ctx.palette.accent(), ctx.cardinalGlow)
                    : ctx.palette.tick();
            CompassStyleContext.vline(g, x, y, 10f, 1.8f, rgb, alpha * 0.85f);
        } else {
            // 5 度位细刻度。
            CompassStyleContext.vline(g, x, y, 6f, 1f, ctx.palette.tick(), alpha * 0.45f);
        }
    }

    @Override
    public void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                             float x, String label, boolean nearest, float alpha) {
        // 方位词：“北”金色高亮，其余白。原作的中文方位词体系。
        boolean isNorth = "N".equals(label) || "北".equals(label);
        float glow = nearest ? ctx.cardinalGlow : 0;
        int rgb = isNorth ? GOLD : ctx.palette.text();
        if (glow > 0.03f && !isNorth) {
            rgb = CompassStyleContext.blend(rgb, ctx.palette.accent(), glow * 0.7f);
        }
        float scale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * 1.18f * (1f + glow * 0.1f));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, Component.literal(label), x, y - 9.75f * scale,
                scale, rgb, Math.min(1f, alpha * (1f + glow * 0.15f)), false);
    }

    @Override
    public void drawIntercardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                                  float x, String label, float alpha) {
        // 东北/东南等：与基数方位词同体系、稍小。
        float scale = 1.0f;
        CompassPaint.centeredScaled(font, g, Component.literal(label), x,
                ctx.originY + labelBaselineY() + ctx.skewAt(x) - 9.75f * scale, scale,
                ctx.palette.text(), alpha * 0.92f, false);
    }

    @Override
    public void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                           float x, int degrees, float alpha) {
        // 原作三位补零（015 / 030 / 105 …）。
        // v1.5.3：与相邻方位字母碰撞时跳过（基数字母 1.18 倍 + 发光放大；数字三位更宽）。
        float cardinalScale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue()
                * 1.18f * 1.10f);
        if (numberOverlapsLetter(ctx, font, x, degrees, String.format("%03d", degrees),
                0.82f, cardinalScale, 1.0f)) {
            return;
        }
        var text = Component.literal(String.format("%03d", degrees))
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float scale = 0.82f;
        CompassPaint.centeredScaled(font, g, text, x,
                ctx.originY + labelBaselineY() + ctx.skewAt(x) - 9.75f * scale, scale,
                ctx.palette.dim(), alpha * 0.95f, false);
    }

    @Override
    public void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g) {
        float cx = ctx.centerX;
        float glow = ctx.cardinalGlow;
        int degrees = Math.round(ctx.heading) % 360;
        String number = CompassConfig.DEGREE_SYMBOL.get() ? degrees + "\u00B0" : String.valueOf(degrees);
        var digits = Component.literal(number)
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));

        // 大读数：字号约为标签 2.2 倍（原作 [338] 的视觉权重）。
        float scale = 1.9f;
        float digitW = font.width(digits) * scale;
        float boxW = digitW + 26f;   // 两侧留出方括号空间
        float boxY = ctx.originY + 4f;
        float boxH = 38f;
        float boxA = (0.80f + glow * 0.12f) * ctx.alpha;

        // 深色底衬（嵌在条带里，略高出条带上下缘）。
        g.fill(Math.round(cx - boxW / 2f), Math.round(boxY), Math.round(cx + boxW / 2f),
                Math.round(boxY + boxH), CompassStyleContext.rgba(ctx.palette.background(), boxA));

        // 左右方括号 [ ]：竖杠 + 上下短臂。
        int brRgb = CompassStyleContext.blend(ctx.palette.dim(), ctx.palette.accent(), 0.35f + glow * 0.6f);
        int brColor = (Math.round((0.75f + glow * 0.25f) * ctx.alpha * 255f) << 24) | (brRgb & 0xFFFFFF);
        drawBracket(g, cx - boxW / 2f + 4f, boxY, boxH, brColor, true);
        drawBracket(g, cx + boxW / 2f - 4f, boxY, boxH, brColor, false);

        // 大号白色读数（吸附时向主题色过渡），在底衬框内垂直居中。
        int rgb = glow > 0.03f
                ? CompassStyleContext.blend(0xFFFFFF, ctx.palette.accent(), glow * 0.85f)
                : 0xFFFFFF;
        CompassPaint.centeredScaled(font, g, digits, cx, boxY + (boxH - 9.75f * scale) / 2f,
                scale, rgb, ctx.alpha, false);

        // 读数正下方的下指三角：与底衬框留出呼吸感，指示“当前朝向”。
        CompassPaint.triangleDown(g, cx, boxY + boxH + 2.5f, 3.2f, 4,
                CompassStyleContext.blend(ctx.palette.dim(), ctx.palette.accent(), 0.4f + glow * 0.6f),
                ctx.alpha * (0.75f + glow * 0.25f));
    }

    /** 方括号一段：竖杠（2px）+ 上下内伸短臂（4px）。 */
    private static void drawBracket(GuiGraphics g, float x, float y, float h, int color, boolean left) {
        int bx = Math.round(x);
        int t = Math.round(y);
        int b = Math.round(y + h);
        int armDir = left ? 1 : -1;
        // 竖杠
        g.fill(bx, t + 3, bx + 2, b - 3, color);
        // 上臂 / 下臂（向括号内侧伸 4px）
        int armX1 = Math.min(bx, bx + 4 * armDir);
        int armX2 = Math.max(bx + 2, bx + 2 + 4 * armDir);
        g.fill(armX1, t + 3, armX2, t + 5, color);
        g.fill(armX1, b - 5, armX2, b - 3, color);
    }

    @Override
    public void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                           CompassMark mark, float x, float alpha, @Nullable String text) {
        float pulse = ctx.markerPulse
                ? 1f + 0.13f * (float) Math.sin((ctx.now - mark.createdAtMillis()) / 280.0 * Math.PI * 2)
                : 1f;
        float y = ctx.originY + markerY();
        float half = 2.7f * pulse;
        // 三角洲风：柔和外晕 + 实际标点同款核心图标。
        markerShape(g, mark, x, y, half + 1.8f, alpha * 0.22f);
        markerShape(g, mark, x, y, half, alpha);
        if (text != null && alpha > 0.35f) {
            var label = Component.literal(text)
                    .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
            CompassPaint.centeredScaled(font, g, label, x, y + 5.5f, 0.72f, mark.color(), alpha * 0.9f, false);
        }
    }
}
