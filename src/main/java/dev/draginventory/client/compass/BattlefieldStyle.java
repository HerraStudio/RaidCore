package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 战地皮肤：无底色丝带——只有一条刻度基准线与两端包角，文字白净纤细，
 * 顶部小三角 + 中线指示。取自 Battlefield 系列罗盘丝带的"轻到几乎不存在"
 * 的设计：不挡画面，纯靠文字与线条传达方位。
 */
final class BattlefieldStyle extends CompassStyle {

    @Override
    public String id() {
        return "battlefield";
    }

    @Override
    public String nameKey() {
        return "draginventory.compass.style.battlefield";
    }

    // ==================== 布局 ====================

    @Override
    public float labelBaselineY() {
        return 21f;
    }

    @Override
    public float tickTopY() {
        return 27f;
    }

    @Override
    public float tickHeight(TickKind kind) {
        return switch (kind) {
            case CARDINAL -> 9f;
            case MAJOR -> 6.5f;
            case MINOR -> 4f;
        };
    }

    @Override
    public float markerY() {
        return 53f;
    }

    @Override
    public float widgetHeight() {
        // 标点文字行最低到 y+65.5（读数胶囊下移后）。
        return 68f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackground(CompassStyleContext ctx, GuiGraphics g) {
        float x = ctx.originX, w = ctx.width;
        float baseY = ctx.originY + 37.5f;
        // 刻度基准线：所有刻度"立"在这条线上。
        CompassStyleContext.hline(g, x + 1, x + w - 1, baseY, 1.5f, ctx.palette.tick(), 0.5f * ctx.alpha);
        // 两端包角（L 形），战地丝带的锚点。
        float armY = ctx.originY + 30f;
        int rgb = ctx.palette.accent();
        float a = 0.7f * ctx.alpha;
        g.fill(Math.round(x), Math.round(armY), Math.round(x + 1.5f), Math.round(baseY + 1.5f),
                CompassStyleContext.rgba(rgb, a));
        g.fill(Math.round(x), Math.round(armY), Math.round(x + 7f), Math.round(armY + 1.5f),
                CompassStyleContext.rgba(rgb, a));
        g.fill(Math.round(x + w - 1.5f), Math.round(armY), Math.round(x + w), Math.round(baseY + 1.5f),
                CompassStyleContext.rgba(rgb, a));
        g.fill(Math.round(x + w - 7f), Math.round(armY), Math.round(x + w), Math.round(armY + 1.5f),
                CompassStyleContext.rgba(rgb, a));
        // 极轻的底衬：仅刻度行，保证浅色环境下可读。
        CompassPaint.gradientH(g, x + w * 0.1f, x + w * 0.9f, ctx.originY + 24, 15,
                ctx.palette.background(), 0.05f * ctx.alpha, 0.16f * ctx.alpha, 8);
        CompassPaint.gradientH(g, x + w * 0.1f, x + w * 0.9f, ctx.originY + 24, 15,
                ctx.palette.background(), 0.16f * ctx.alpha, 0.05f * ctx.alpha, 8);
    }

    @Override
    public void drawTick(CompassStyleContext ctx, GuiGraphics g, float x, TickKind kind,
                         int degrees, float alpha) {
        float y = ctx.originY + tickTopY() + ctx.skewAt(x);
        switch (kind) {
            case CARDINAL -> {
                boolean nearest = Math.abs(x - ctx.centerX) < 3f && ctx.cardinalGlow > 0.03f;
                int rgb = nearest
                        ? CompassStyleContext.blend(ctx.palette.tick(), ctx.palette.accent(), ctx.cardinalGlow)
                        : ctx.palette.tick();
                CompassStyleContext.vline(g, x, y, tickHeight(kind), 1.8f, rgb,
                        alpha * (nearest ? 0.9f : 0.75f));
            }
            case MAJOR -> CompassStyleContext.vline(g, x, y, tickHeight(kind), 1f,
                    ctx.palette.tick(), alpha * 0.7f);
            default -> CompassStyleContext.vline(g, x, y, tickHeight(kind), 1f,
                    ctx.palette.tick(), alpha * 0.4f);
        }
    }

    @Override
    public void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                             float x, String label, boolean nearest, float alpha) {
        float glow = nearest ? ctx.cardinalGlow : 0;
        int rgb = CompassStyleContext.blend(ctx.palette.text(), ctx.palette.accent(), glow * 0.85f);
        float scale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue() * (1f + glow * 0.14f));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, Component.literal(label), x, y - 10f, scale, rgb,
                Math.min(1f, alpha * (1f + glow * 0.2f)), false);
    }

    @Override
    public void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                           float x, int degrees, float alpha) {
        // v1.5.3：与相邻方位字母碰撞时跳过（基数字母 CARDINAL_SCALE + 发光放大）。
        float cardinalScale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue() * 1.14f);
        if (numberOverlapsLetter(ctx, font, x, degrees, String.valueOf(degrees),
                0.78f, cardinalScale, 1.0f)) {
            return;
        }
        var text = Component.literal(String.valueOf(degrees))
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, text, x, y - 8f, 0.78f, ctx.palette.dim(), alpha * 0.95f, false);
    }

    @Override
    public void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g) {
        float cx = ctx.centerX;
        float glow = ctx.cardinalGlow;
        // 顶部小下指三角 + 中线（战地式指示）。
        int rgb = CompassStyleContext.blend(0xFFFFFF, ctx.palette.accent(), glow * 0.6f);
        CompassPaint.triangleDown(g, cx, ctx.originY + 1f, 3f, 4, rgb, ctx.alpha * 0.9f);
        CompassStyleContext.vline(g, cx, ctx.originY + 5.5f, 6.5f, 1f, rgb,
                ctx.alpha * (0.5f + glow * 0.25f));
        // 基准线中央高亮段：吸附时变亮变宽。
        float baseY = ctx.originY + 37.5f;
        CompassStyleContext.hline(g, cx - 9f, cx + 9f, baseY, 1.5f, ctx.palette.accent(),
                (0.45f + glow * 0.55f) * ctx.alpha);
        // 条带下方大号度数（原作读数是 HUD 中最显眼的元素；底衬保证与标点交叠时可读）。
        int degrees = Math.round(ctx.heading) % 360;
        String number = CompassConfig.DEGREE_SYMBOL.get() ? degrees + "\u00B0" : String.valueOf(degrees);
        var digits = Component.literal(number)
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float digitW = font.width(digits) * 0.85f;
        CompassPaint.roundedRect(g, cx - digitW / 2f - 6f, ctx.originY + 40f, digitW + 12f, 12f,
                ctx.palette.background(), 0.6f * ctx.alpha, 2);
        CompassPaint.centeredScaled(font, g, digits, cx, ctx.originY + 42.5f, 0.85f,
                glow > 0.03f
                        ? CompassStyleContext.blend(ctx.palette.text(), ctx.palette.accent(), glow)
                        : ctx.palette.text(),
                ctx.alpha * 0.95f, false);
    }

    @Override
    public void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                           CompassMark mark, float x, float alpha, @Nullable String text) {
        float pulse = ctx.markerPulse
                ? 1f + 0.12f * (float) Math.sin((ctx.now - mark.createdAtMillis()) / 290.0 * Math.PI * 2)
                : 1f;
        float y = ctx.originY + markerY();
        float half = 2.6f * pulse;
        // 战地风：外框描边 + 中心分型形状（军事标记牌的轮廓感）。
        CompassPaint.squareOutline(g, x, y, half + 2.2f, 1f, mark.color(), alpha * 0.5f);
        markerShape(g, mark, x, y, half, alpha);
        if (text != null && alpha > 0.35f) {
            var label = Component.literal(text)
                    .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
            CompassPaint.centeredScaled(font, g, label, x, y + 5.5f, 0.7f, mark.color(), alpha * 0.9f, false);
        }
    }
}
