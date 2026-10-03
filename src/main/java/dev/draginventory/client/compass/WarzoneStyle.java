package dev.draginventory.client.compass;

import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 使命召唤皮肤：圆角半透明条带，中央固定读数块——大号当前方位字
 * （八方位，如 北/东北/N/NE）+ 下方小号精确度数，细中线贯穿。
 * 取自 COD 现代战争/战区罗盘：滚动内容 + 中央"字母块"读数的组合。
 */
final class WarzoneStyle extends CompassStyle {

    @Override
    public String id() {
        return "warzone";
    }

    @Override
    public String nameKey() {
        return "draginventory.compass.style.warzone";
    }

    // ==================== 布局 ====================

    @Override
    public float labelBaselineY() {
        return 24f;
    }

    @Override
    public float tickTopY() {
        return 30f;
    }

    @Override
    public float tickHeight(TickKind kind) {
        return switch (kind) {
            case CARDINAL -> 8f;
            case MAJOR -> 5.5f;
            case MINOR -> 3.5f;
        };
    }

    @Override
    public float markerY() {
        return 56f;
    }

    @Override
    public float widgetHeight() {
        // 标点文字行最低到 y+68（条带下方读数行下移后）。
        return 70f;
    }

    // ==================== 绘制 ====================

    @Override
    public void drawBackground(CompassStyleContext ctx, GuiGraphics g) {
        float x = ctx.originX, y = ctx.originY + 10, w = ctx.width, h = 32;
        // 圆角条带：两端 12% 渐隐（在条带自身高度内）。
        float a = 0.55f * ctx.alpha;
        float fade = w * 0.12f;
        int l = Math.round(x + fade), r = Math.round(x + w - fade);
        int t = Math.round(y), b = Math.round(y + h);
        int c = CompassStyleContext.rgba(ctx.palette.background(), a);
        // 主体 + 2px 阶梯圆角。
        g.fill(l + 2, t, r - 2, b, c);
        g.fill(l, t + 2, l + 2, b - 2, c);
        g.fill(r - 2, t + 2, r, b - 2, c);
        CompassPaint.gradientH(g, x, x + fade, y, h, ctx.palette.background(), 0f, a, 10);
        CompassPaint.gradientH(g, x + w - fade, x + w, y, h, ctx.palette.background(), a, 0f, 10);
        // 顶底 1px 边线。
        int edge = CompassStyleContext.rgba(ctx.palette.text(), 0.12f * ctx.alpha);
        g.fill(l + 2, t, r - 2, t + 1, edge);
        g.fill(l + 2, b - 1, r - 2, b, edge);
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
                CompassStyleContext.vline(g, x, y, tickHeight(kind), 1.6f, rgb, alpha * 0.85f);
            }
            case MAJOR -> CompassStyleContext.vline(g, x, y, tickHeight(kind), 1f,
                    ctx.palette.tick(), alpha * 0.7f);
            default -> CompassStyleContext.vline(g, x, y, tickHeight(kind), 1f,
                    ctx.palette.tick(), alpha * 0.45f);
        }
    }

    @Override
    public void drawCardinal(CompassStyleContext ctx, Font font, GuiGraphics g,
                             float x, String label, boolean nearest, float alpha) {
        float glow = nearest ? ctx.cardinalGlow : 0;
        int rgb = CompassStyleContext.blend(ctx.palette.text(), ctx.palette.accent(), glow * 0.85f);
        float scale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue() * (1f + glow * 0.12f));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, Component.literal(label), x, y - 10f, scale, rgb,
                Math.min(1f, alpha * (1f + glow * 0.2f)), false);
    }

    @Override
    public void drawNumber(CompassStyleContext ctx, Font font, GuiGraphics g,
                           float x, int degrees, float alpha) {
        // v1.5.3：与相邻方位字母碰撞时跳过（基数字母 CARDINAL_SCALE + 发光放大）。
        float cardinalScale = (float) (CompassConfig.CARDINAL_SCALE.get().doubleValue() * 1.12f);
        if (numberOverlapsLetter(ctx, font, x, degrees, String.valueOf(degrees),
                0.8f, cardinalScale, 1.0f)) {
            return;
        }
        var text = Component.literal(String.valueOf(degrees))
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float y = ctx.originY + labelBaselineY() + ctx.skewAt(x);
        CompassPaint.centeredScaled(font, g, text, x, y - 8f, 0.8f, ctx.palette.dim(), alpha * 0.95f, false);
    }

    @Override
    public void drawCenter(CompassStyleContext ctx, Font font, GuiGraphics g) {
        float cx = ctx.centerX;
        float glow = ctx.cardinalGlow;
        int degrees = Math.round(ctx.heading) % 360;
        // COD 标志组合：顶部中央白色下指三角 + 短中线。
        int triRgb = CompassStyleContext.blend(0xFFFFFF, ctx.palette.accent(), glow * 0.5f);
        CompassPaint.triangleDown(g, cx, ctx.originY + 0.5f, 3.2f, 4, triRgb,
                ctx.alpha * (0.9f + glow * 0.1f));
        CompassStyleContext.vline(g, cx, ctx.originY + 4.5f, 5f, 1.2f, triRgb,
                ctx.alpha * (0.55f + glow * 0.2f));
        // 条带下方单行读数："方位名 + 度数"（如 北 353 / NE 22），COD 同款格式。
        // 无底衬，靠文字阴影可读；方位字吸附时向主题色过渡。
        int nearest8 = Math.floorMod(Math.round(ctx.heading / 45f) * 45, 360);
        String name = nearest8 % 90 == 0
                ? CompassWidget.cardinalName(nearest8)
                : CompassWidget.intercardinalName(nearest8);
        var nameText = Component.literal(name);
        String number = CompassConfig.DEGREE_SYMBOL.get() ? degrees + "\u00B0" : String.valueOf(degrees);
        var digits = Component.literal(number)
                .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
        float nameW = font.width(nameText);
        float digitW = font.width(digits) * 0.85f;
        float gap = 4f;
        float totalW = nameW + gap + digitW;
        float readY = ctx.originY + 45f;
        int nameRgb = glow > 0.03f
                ? CompassStyleContext.blend(0xFFFFFF, ctx.palette.accent(), glow * 0.85f)
                : 0xFFFFFF;
        CompassStyleContext.text(font, g, nameText, cx - totalW / 2f, readY, nameRgb,
                ctx.alpha, true);
        // 数字段中心 = cx + (totalW - digitW) / 2（名字之后、整体右半段）。
        CompassPaint.centeredScaled(font, g, digits, cx + (totalW - digitW) / 2f,
                readY, 0.85f, ctx.palette.dim(), ctx.alpha * 0.95f, true);
    }

    @Override
    public void drawMarker(CompassStyleContext ctx, Font font, GuiGraphics g,
                           CompassMark mark, float x, float alpha, @Nullable String text) {
        float pulse = ctx.markerPulse
                ? 1f + 0.13f * (float) Math.sin((ctx.now - mark.createdAtMillis()) / 285.0 * Math.PI * 2)
                : 1f;
        float y = ctx.originY + markerY();
        float half = 2.6f * pulse;
        // 战区风：小实心形状 + 底部短尾线（标记钉在条带边缘的感觉）。
        markerShape(g, mark, x, y, half, alpha);
        CompassStyleContext.vline(g, x, y + half + 1f, 2.5f, 1f, mark.color(), alpha * 0.6f);
        if (text != null && alpha > 0.35f) {
            var label = Component.literal(text)
                    .withStyle(s -> s.withFont(com.lowdragmc.lowdraglib2.gui.LDLibFonts.JETBRAINS_MONO_BOLD));
            CompassPaint.centeredScaled(font, g, label, x, y + 5.5f, 0.7f, mark.color(), alpha * 0.9f, false);
        }
    }
}
