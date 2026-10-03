package dev.draginventory.client.compass;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 方位条渲染上下文：一次渲染帧内的全部状态与绘制辅助。
 * 控件负责布局与动画计算，皮肤只通过本类提供的几何信息与工具方法绘制。
 */
final class CompassStyleContext {
    /** 控件局部坐标系下的几何（未应用整体缩放）。 */
    final float originX, originY, width, height, centerX;
    /** 平滑航向（连续值，度）。 */
    final float heading;
    /** 平滑角速度（度/秒）。 */
    final float velocity;
    /** 基数方位接近度 [0,1] 与最近基数方位。 */
    final float cardinalGlow;
    final int nearestCardinal;
    /** 全局不透明度（配置 × 入场动画）。 */
    final float alpha;
    /** 入场动画进度 [0,1]（1 = 完成），关闭入场动画时恒为 1。 */
    final float entryProgress;
    /** 当前毫秒时间（动画用）。 */
    final long now;
    /** 当前配色（已应用颜色覆盖）。 */
    final CompassPalette palette;
    /** 当前皮肤实例。 */
    final CompassStyle style;
    /** 本帧缓存的可见视野总角度（度）：避免 degreesToX/edgeFade 每元素一次 ConfigValue 查询。 */
    final float range;
    /** 预计算的惯性倾斜系数（含速度/强度，关闭时为 0）。 */
    final float tiltFactor;
    /** 本帧缓存的标点脉冲开关。 */
    final boolean markerPulse;

    CompassStyleContext(float originX, float originY, float width, float height,
                        float heading, float velocity, float cardinalGlow, int nearestCardinal,
                        float alpha, float entryProgress, long now,
                        CompassPalette palette, CompassStyle style) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.centerX = originX + width / 2f;
        this.heading = heading;
        this.velocity = velocity;
        this.cardinalGlow = cardinalGlow;
        this.nearestCardinal = nearestCardinal;
        this.alpha = alpha;
        this.entryProgress = entryProgress;
        this.now = now;
        this.palette = palette;
        this.style = style;
        // 帧内不变的配置值在此一次性读取缓存，绘制热路径（每刻度/每标点）零配置查询。
        this.range = CompassConfig.RANGE.get();
        this.markerPulse = CompassConfig.MARKERS_PULSE.get();
        this.tiltFactor = CompassConfig.INERTIA_TILT.get()
                ? Mth.clamp(velocity / 200f, -1.6f, 1.6f)
                * (float) CompassConfig.TILT_INTENSITY.get().doubleValue() * 2.4f
                : 0f;
    }

    /** 度数差 -> 控件局部 x 坐标。 */
    float degreesToX(float degrees) {
        float diff = CompassHeading.wrapDegrees(degrees - heading);
        float half = range / 2f;
        return centerX + diff / half * (width / 2f - 6f);
    }

    /** 边缘渐隐系数 [0,1]：跨度由皮肤决定（PUBG 硬截止 → 恒 1）。 */
    float edgeFade(float x) {
        float span = style.edgeFadeSpan();
        if (span <= 0f) return 1f;
        float t = Math.abs(x - centerX) / (width / 2f);
        return 1f - smoothstep(Mth.clamp((t - (1f - span)) / span, 0f, 1f));
    }

    /** 惯性倾斜：转向时刻度/标签沿条带方向的剪切位移。 */
    float skewAt(float x) {
        if (tiltFactor == 0f) return 0f;
        return tiltFactor * ((x - centerX) / (width / 2f));
    }

    /** 合成带透明度的 ARGB 颜色。 */
    static int rgba(int rgb, float alpha) {
        return (Math.round(Mth.clamp(alpha, 0f, 1f) * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    /** 平滑阶梯。 */
    static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }

    /** 颜色线性混合（RGB）。 */
    static int blend(int from, int to, float t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = Math.round(fr + (tr - fr) * t);
        int gg = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return (r << 16) | (gg << 8) | b;
    }

    /** 绘制细线（水平），带 0.5px 半透明边模拟抗锯齿。 */
    static void hline(GuiGraphics g, float x1, float x2, float y, float thickness, int rgb, float alpha) {
        int c = rgba(rgb, alpha);
        g.fill(Math.round(x1), Math.round(y), Math.round(x2), Math.round(y + thickness), c);
        if (alpha > 0.15f) {
            int halo = rgba(rgb, alpha * 0.28f);
            g.fill(Math.round(x1), Math.round(y - 0.5f), Math.round(x2), Math.round(y), halo);
            g.fill(Math.round(x1), Math.round(y + thickness), Math.round(x2), Math.round(y + thickness + 0.5f), halo);
        }
    }

    /** 绘制竖线（刻度），带柔边。 */
    static void vline(GuiGraphics g, float x, float y, float height, float thickness, int rgb, float alpha) {
        int c = rgba(rgb, alpha);
        int left = Math.round(x - thickness / 2f);
        int right = Math.round(x + thickness / 2f);
        g.fill(left, Math.round(y), right, Math.round(y + height), c);
    }

    /** 平滑字体文本（LDLib2 管线，非像素风）。返回文本宽度。 */
    static int text(Font font, GuiGraphics g, Component component, float x, float y, int rgb, float alpha, boolean shadow) {
        return com.lowdragmc.lowdraglib2.gui.LDLibFonts.drawText(g, font, component, x, y, rgba(rgb, alpha), shadow);
    }
}
