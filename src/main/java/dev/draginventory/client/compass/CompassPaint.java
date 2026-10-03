package dev.draginventory.client.compass;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 方位条绘制工具：全部基于原版 fill / LDLib2 平滑字体与姿态栈，
 * 不依赖额外纹理资源，保证在任何资源包下观感一致。
 */
final class CompassPaint {
    private CompassPaint() {}

    /** 居中缩放文本（平滑字体，子像素定位）。 */
    static void centeredScaled(Font font, GuiGraphics g, Component text, float centerX,
                               float baselineY, float scale, int rgb, float alpha, boolean shadow) {
        if (alpha <= 0.01f) return;
        g.pose().pushPose();
        try {
            g.pose().translate(centerX, baselineY, 0);
            g.pose().scale(scale, scale, 1);
            float w = font.width(text);
            CompassStyleContext.text(font, g, text, -w / 2f, 0, rgb, alpha, shadow);
        } finally {
            g.pose().popPose();
        }
    }

    /** 发光竖线：核心 + 两层光晕。 */
    static void glowVLine(GuiGraphics g, float x, float top, float height, float coreWidth,
                          int rgb, float alpha, boolean glow) {
        CompassStyleContext.vline(g, x, top, height, coreWidth, rgb, alpha);
        if (glow && alpha > 0.05f) {
            CompassStyleContext.vline(g, x, top - 1, height + 2, coreWidth + 2.5f, rgb, alpha * 0.22f);
            CompassStyleContext.vline(g, x, top - 2, height + 4, coreWidth + 5.5f, rgb, alpha * 0.10f);
        }
    }

    /** 旋转 45° 的实心菱形（标点用）。 */
    static void diamond(GuiGraphics g, float cx, float cy, float half, int rgb, float alpha) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        int left = Math.round(cx - half);
        int right = Math.round(cx + half);
        int top = Math.round(cy - half);
        int bottom = Math.round(cy + half);
        // 逐行绘制菱形（每行宽度按到中心的距离收窄），避免姿态旋转的锯齿。
        int size = Math.max(1, Math.round(half * 2));
        for (int i = 0; i < size; i++) {
            float rowCenter = top + i + 0.5f;
            float dist = Math.abs(rowCenter - cy) / half;
            float rowHalf = Math.max(0, half * (1 - dist));
            if (rowHalf < 0.35f) continue;
            g.fill(Math.round(cx - rowHalf), Math.round(rowCenter - 0.5f),
                    Math.round(cx + rowHalf), Math.round(rowCenter + 0.5f), c);
        }
    }

    /** 方形描边（战术风格标点）。 */
    static void squareOutline(GuiGraphics g, float cx, float cy, float half, float thickness,
                              int rgb, float alpha) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        int left = Math.round(cx - half), right = Math.round(cx + half);
        int top = Math.round(cy - half), bottom = Math.round(cy + half);
        int t = Math.max(1, Math.round(thickness));
        g.fill(left, top, right, top + t, c);
        g.fill(left, bottom - t, right, bottom, c);
        g.fill(left, top, left + t, bottom, c);
        g.fill(right - t, top, right, bottom, c);
    }

    /** 2 段阶梯圆角矩形（现代感的柔和边角，无需 SDF 纹理）。 */
    static void roundedRect(GuiGraphics g, float x, float y, float w, float h,
                            int rgb, float alpha, int radius) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        int left = Math.round(x), right = Math.round(x + w);
        int top = Math.round(y), bottom = Math.round(y + h);
        if (radius <= 0) {
            g.fill(left, top, right, bottom, c);
            return;
        }
        int r = Math.min(radius, (int) (Math.min(w, h) / 2f));
        // 主体（内缩 r）
        g.fill(left + r, top, right - r, bottom, c);
        // 上下边条
        g.fill(left, top + r, left + r, bottom - r, c);
        g.fill(right - r, top + r, right, bottom - r, c);
        // 四角阶梯
        for (int i = 1; i <= r; i++) {
            int inset = (int) Math.round(r - Math.sqrt(r * r - (r - i + 0.5) * (r - i + 0.5)) + 0.5);
            inset = Mth.clamp(inset, 0, r);
            g.fill(left + inset, top + r - i, left + inset + 1, top + r - i + 1, c);
            g.fill(right - inset - 1, top + r - i, right - inset, top + r - i + 1, c);
            g.fill(left + inset, bottom - r + i - 1, left + inset + 1, bottom - r + i, c);
            g.fill(right - inset - 1, bottom - r + i - 1, right - inset, bottom - r + i, c);
        }
    }

    /** 下指小三角（中心指示器）。 */
    static void triangleDown(GuiGraphics g, float cx, float topY, float halfWidth, int rows,
                             int rgb, float alpha) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        for (int i = 0; i < rows; i++) {
            float w = halfWidth * (1f - i / (float) rows);
            if (w < 0.4f) break;
            g.fill(Math.round(cx - w), Math.round(topY + i), Math.round(cx + w), Math.round(topY + i + 1), c);
        }
    }

    /**
     * 45 度切角矩形（Apex 风读数底衬）：四角内收 ch 像素的八边形轮廓，
     * 可选 1px 描边（edgeAlpha <= 0 时不画）。
     */
    static void chamferRect(GuiGraphics g, float x, float y, float w, float h, int ch,
                            int fillRgb, float fillAlpha, int edgeRgb, float edgeAlpha) {
        int c = CompassStyleContext.rgba(fillRgb, fillAlpha);
        int left = Math.round(x), right = Math.round(x + w);
        int top = Math.round(y), bottom = Math.round(y + h);
        int k = Math.min(Math.round(ch), (int) (Math.min(w, h) / 2f));
        // 主体三段横带（上下内缩 k，中段全宽）
        g.fill(left + k, top, right - k, top + k > bottom ? bottom : top + k, c);
        g.fill(left, top + k, right, bottom - k, c);
        g.fill(left + k, bottom - k < top ? top : bottom - k, right - k, bottom, c);
        // 斜角阶梯填充（两级）
        for (int i = 0; i < k; i++) {
            g.fill(left + i, top + k - i - 1, left + i + 1, top + k - i, c);
            g.fill(right - i - 1, top + k - i - 1, right - i, top + k - i, c);
            g.fill(left + i, bottom - k + i, left + i + 1, bottom - k + i + 1, c);
            g.fill(right - i - 1, bottom - k + i, right - i, bottom - k + i + 1, c);
        }
        if (edgeAlpha > 0.01f) {
            int e = CompassStyleContext.rgba(edgeRgb, edgeAlpha);
            // 顶底横边（避开切角）
            g.fill(left + k, top, right - k, top + 1, e);
            g.fill(left + k, bottom - 1, right - k, bottom, e);
            // 左右竖边（避开切角）
            g.fill(left, top + k, left + 1, bottom - k, e);
            g.fill(right - 1, top + k, right, bottom - k, e);
            // 四个斜边（逐级阶梯）
            for (int i = 0; i < k; i++) {
                g.fill(left + k - i - 1, top + i, left + k - i, top + i + 1, e);
                g.fill(right - k + i, top + i, right - k + i + 1, top + i + 1, e);
                g.fill(left + k - i - 1, bottom - i - 1, left + k - i, bottom - i, e);
                g.fill(right - k + i, bottom - i - 1, right - k + i + 1, bottom - i, e);
            }
        }
    }

    /** 上指等腰三角（LOCATION 标点，垂直居中于 cy）。 */
    static void triangleUp(GuiGraphics g, float cx, float cy, float halfWidth, int rows,
                           int rgb, float alpha) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        float top = cy - rows / 2f;
        for (int i = 0; i < rows; i++) {
            // 自上而下逐渐加宽：顶行最窄，底行最宽。
            float w = halfWidth * ((i + 1f) / rows);
            g.fill(Math.round(cx - w), Math.round(top + i), Math.round(cx + w), Math.round(top + i + 1), c);
        }
    }

    /** X 十字（DEATH 标点）：两条对角线，逐行两段 fill，避免姿态旋转的锯齿。 */
    static void crossX(GuiGraphics g, float cx, float cy, float half, int rgb, float alpha) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        int rows = Math.max(2, Math.round(half * 2));
        float top = cy - half;
        for (int i = 0; i < rows; i++) {
            float t = (i + 0.5f) / rows;          // 0..1
            float offset = (t - 0.5f) * half * 2f; // -half..+half
            float thickness = Math.max(0.6f, half / rows * 1.4f);
            int y1 = Math.round(top + i);
            int y2 = Math.round(top + i + 1);
            // 主对角线（左上 -> 右下）与副对角线（右上 -> 左下）各画一小段。
            g.fill(Math.round(cx + offset - thickness / 2), y1,
                    Math.round(cx + offset + thickness / 2), y2, c);
            g.fill(Math.round(cx - offset - thickness / 2), y1,
                    Math.round(cx - offset + thickness / 2), y2, c);
        }
    }

    /** 向左的小箭头（屏外标点方向指示，指示“往左转”）。 */
    static void chevronLeft(GuiGraphics g, float cx, float cy, float half, int rgb, float alpha) {
        chevron(g, cx, cy, half, rgb, alpha, true);
    }

    /** 向右的小箭头（屏外标点方向指示，指示“往右转”）。 */
    static void chevronRight(GuiGraphics g, float cx, float cy, float half, int rgb, float alpha) {
        chevron(g, cx, cy, half, rgb, alpha, false);
    }

    /** 单向箭头符（"<" 或 ">"）：两段斜线拼成。 */
    private static void chevron(GuiGraphics g, float cx, float cy, float half,
                                int rgb, float alpha, boolean left) {
        int c = CompassStyleContext.rgba(rgb, alpha);
        int rows = Math.max(3, Math.round(half * 2));
        // 箭头尖朝向翻转侧："<" 尖在左（cx-half），">" 尖在右（cx+half）。
        float tipX = left ? cx - half : cx + half;
        for (int i = 0; i < rows; i++) {
            float t = Math.abs((i + 0.5f) / rows * 2f - 1f); // 1(顶/底) -> 0(中)
            float dx = t * half;                               // 距箭头尖的水平距离
            int y1 = Math.round(cy - half + i);
            int y2 = Math.round(cy - half + i + 1);
            float x = tipX + (left ? dx : -dx);
            g.fill(Math.round(x - 0.5f), y1, Math.round(x + 0.5f), y2, c);
        }
    }

    /** 水平渐变填充（从 x1 到 x2，alpha1 -> alpha2）。 */
    static void gradientH(GuiGraphics g, float x1, float x2, float y, float h,
                          int rgb, float a1, float a2, int segments) {
        float segW = (x2 - x1) / segments;
        for (int i = 0; i < segments; i++) {
            float t = (i + 0.5f) / segments;
            float a = Mth.lerp(t, a1, a2);
            if (a <= 0.004f) continue;
            g.fill(Math.round(x1 + i * segW), Math.round(y),
                    Math.round(x1 + (i + 1) * segW + 0.5f), Math.round(y + h),
                    CompassStyleContext.rgba(rgb, a));
        }
    }

    // ==================== 实际标点同款图标（与 3D 战术标点视觉一致） ====================

    /**
     * 红色感叹号（ENEMY 标点）：几何与 3D 战术标点的 "!" 同款——
     * 竖条 + 底部圆点 + 半透明阴影衬底，任意 GUI 缩放下保持锐利。
     */
    static void exclamation(GuiGraphics g, float cx, float cy, float scale, int rgb, float alpha) {
        int body = CompassStyleContext.rgba(rgb, alpha);
        int shadow = CompassStyleContext.rgba(0x000000, alpha * 0.55f);
        int w = Math.max(2, Math.round(3 * scale));
        int barTop = Math.round(cy - 7 * scale);
        int barBottom = Math.round(cy + 3 * scale);
        int dotTop = Math.round(cy + 5 * scale);
        int dotBottom = Math.round(cy + 8 * scale);
        // 阴影衬底（比本体大 1px，与 3D 标点的 shadow 处理一致）
        g.fill(Math.round(cx - w / 2f) - 1, barTop - 1, Math.round(cx + w / 2f) + 1, dotBottom + 1, shadow);
        // 竖条 + 圆点
        g.fill(Math.round(cx - w / 2f), barTop, Math.round(cx + w / 2f), barBottom, body);
        g.fill(Math.round(cx - w / 2f), dotTop, Math.round(cx + w / 2f), dotBottom, body);
    }

    /**
     * 白色菱形（LOCATION 标点）：45° 旋转方块，几何与 3D 战术标点同款
     * （中心 6x6 旋转 45°），外加暗色描边衬底保证浅色天空下依然可见。
     */
    static void locationDiamond(GuiGraphics g, float cx, float cy, float scale, int rgb, float alpha) {
        float half = 3f * scale;
        // 暗色衬底（比本体大 1px 的菱形）
        diamond(g, cx, cy, half + 1f, 0x000000, alpha * 0.55f);
        diamond(g, cx, cy, half, rgb, alpha);
    }

    /**
     * 物品图标（ITEM 标点）：渲染掉落物的物品贴图本体，与 3D 战术标点
     * 显示物品模型一致。size 为目标边长（像素），中心对齐 (cx, cy)。
     * 半透明经 shaderColor 全局乘法实现（与 3D 标点的 alpha 淡出语义一致）。
     */
    static void itemIcon(GuiGraphics g, ItemStack stack, float cx, float cy, float size, float alpha) {
        if (stack == null || stack.isEmpty() || alpha <= 0.02f) return;
        float s = size / 16f;
        g.pose().pushPose();
        try {
            g.pose().translate(cx - 8f * s, cy - 8f * s, 0);
            g.pose().scale(s, s, 1);
            RenderSystem.setShaderColor(1f, 1f, 1f, Mth.clamp(alpha, 0f, 1f));
            try {
                g.renderItem(stack, 0, 0);
                g.flush();
            } finally {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            }
        } finally {
            g.pose().popPose();
        }
    }
}
