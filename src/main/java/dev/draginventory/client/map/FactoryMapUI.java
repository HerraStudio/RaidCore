package dev.draginventory.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.draginventory.client.TacticalMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared tactical drawing vocabulary; icons use crisp GUI geometry. */
final class FactoryMapUI {
    static final int TEXT = 0xFFE0E9E8, DIM = 0xFF829594, GREEN = 0xFF35D8A0;
    static final int LINE = 0xFF304344, PANEL = 0xEB0B1519;
    /** 主题强调色：与 GREEN 同值的语义别名（战术青绿）。 */
    static final int ACCENT = 0xFF35D8A0;
    /** 现代战术面板底色（约 90% 不透明）。 */
    static final int PANEL_BG = 0xE6101820;
    /** 更深层面板底色（低层级区域 / 滑块轨道）。 */
    static final int PANEL_BG_DEEP = 0xF20C1319;
    /** 数字徽标底色。 */
    static final int CHIP_BG = 0xFF1B2A30;
    /** 悬停增亮覆盖色（约 20% 白）。 */
    static final int HOVER = 0x33FFFFFF;
    /** 参考图边框色（暗灰蓝描边）。 */
    static final int STROKE_DIM = 0xFF2D3748;
    private FactoryMapUI() {}
    static void text(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(Minecraft.getInstance().font, text, x, y, color, false);
    }

    /** 缩放绘制的小号文字：pushPose → scale → 以 (x/scale, y/scale) 为基准绘制 → popPose。 */
    static void smallText(GuiGraphics g, String s, int x, int y, int color, float scale) {
        if (s == null || s.isEmpty() || scale <= 0) return;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        g.drawString(Minecraft.getInstance().font, s,
                Math.round(x / scale), Math.round(y / scale), color, false);
        g.pose().popPose();
    }

    /** 缩放后的文字宽度（与 smallText 同一量纲，供右对齐排版）。 */
    static int textWidth(String s, float scale) {
        return Math.round(Minecraft.getInstance().font.width(s) * scale);
    }
    static String tr(String key, Object... args) { return Component.translatable("draginventory.map." + key, args).getString(); }
    static void box(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
    }
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL); box(g, x, y, w, h, LINE);
    }
    static int color(TacticalMarker.Type type) {
        return switch(type) { case LOCATION -> 0xFF67BBD2; case ENEMY -> 0xFFE45E62; case ITEM -> 0xFFE8AF52; };
    }
    static void glyph(GuiGraphics g, int x, int y, TacticalMarker.Type type, int color) {
        g.pose().pushPose(); g.pose().translate(x, y, 0);
        if (type == TacticalMarker.Type.LOCATION) {
            g.pose().mulPose(Axis.ZP.rotationDegrees(45));
            g.fill(-4, -4, 4, 4, 0xEF0B171D); box(g, -4, -4, 8, 8, color); g.fill(-1, -1, 1, 1, color);
        } else if (type == TacticalMarker.Type.ENEMY) {
            box(g, -5, -7, 10, 14, color); g.fill(-1, -4, 1, 1, color); g.fill(-1, 3, 1, 5, color);
        } else {
            g.fill(-6, -4, 6, 5, 0xEF211D14);
            box(g, -6, -4, 12, 9, color); box(g, -3, -6, 6, 3, color); g.fill(-1, -1, 1, 2, color);
        }
        g.pose().popPose();
    }

    // ==================== 玩家导航箭头（v2.5.9 严格遵循用户 SVG 原图，vanilla 纹理管线） ====================

    /**
     * 玩家箭头纹理资源（jar 内 assets/draginventory/textures/gui/player_marker.png）；同目录
     * .mcmeta 声明 blur=true——原版 SimpleTexture 按线性过滤加载（边缘平滑无锯齿）。
     */
    private static final ResourceLocation PLAYER_MARKER_ID =
            ResourceLocation.fromNamespaceAndPath("draginventory", "textures/gui/player_marker.png");
    /**
     * 纹理画布边长（GUI px）：SVG 画布 200×200 等比映射 256×256 纹理；形状（含同色
     * 描边 miter，高约 161/200 画布）最终显示 ≈12.5px——与 v2.5.7 视觉尺寸一致，不变大。
     */
    private static final float PLAYER_CANVAS = 15.5f;
    /** 满速沿前进方向拉伸 / 横向收窄上限（空气动力学感）。 */
    static final float PLAYER_STRETCH = 0.30f, PLAYER_SQUEEZE = 0.12f;

    /**
     * 玩家导航箭头（v2.5.9 严格遵循用户提供的 玩家位置图标.svg 原图，零再创作）：
     * 原图为纯 #32CD32 平面导航箭头（单色填充 + 同色描边，无任何明暗层次），离线
     * 栅格化为 256×256 抗锯齿 PNG 打包进 jar；绘制走原版 GuiGraphics.blit +
     * SimpleTexture 纹理管线——与原版 HUD 全部贴图（快捷栏同款）同一条加载与渲染
     * 路径，纹理随 .mcmeta blur=true 以 LINEAR 线性过滤加载——任意 GUI 缩放/呼吸/
     * 拉伸下边缘平滑如矢量（SVG 本来的观感）。v2.5.8 的自研 AbstractTexture +
     * mipmap 链在实机不显示（静态审计全链路正确但沙箱不可复现，根因未定位），
     * 本版彻底移除全部自研 GL/纹理代码，仅保留原版路径——不存在静默不可见的失败
     * 态：纹理缺失时原版回退紫黑棋盘，游戏内一眼可辨。v2.5.7 的三个问题（左半压
     * 暗立体感/中脊白缝/边缘锯齿）不回归：绘制内容即原图 PNG。
     *
     * <p>视觉尺寸与 v2.5.7 一致：形状（含描边 miter）显示 ≈12.5px。动效参数（来自
     * {@link PlayerMarkerFX}）：stretch 0..1 沿前进方向最多 +30% 拉伸、横向 -12% 收窄；
     * alpha 供尾迹残影叠透（shaderColor 统一乘 α）；scale 供静止呼吸 / 残影缩小。
     * 朝向约定与旧版一致：yaw 0 = +Z = 屏幕下方，纹理尖头朝 -Y，故旋转 180°+yaw。
     *
     * <p>v2.6.0 尺寸选项：最终缩放再乘配置 {@code markers.player_marker_size}
     * （0.4~1.0，1.0 = 默认尺寸 = 上限；设置 GUI 标点页滑条 / 指令 /map player_size
     * 可调）。只在 pose 缩放层叠加倍率，<b>纹理管线（SimpleTexture + .mcmeta
     * 线性过滤）零改动</b>——不会重蹈 v2.5.8 图标不显示的回归；小地图 / 大地图 /
     * 设置预览三处调用点与尾迹残影全部等比缩放。</p>
     */
    static void player(GuiGraphics g, float x, float y, float yaw, float alpha, float stretch, float scale) {
        if (alpha <= 0f) return;
        stretch = Math.min(Math.max(stretch, 0f), 1f);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(180 + yaw));
        float k = PLAYER_CANVAS / 256f * scale * MapConfig.PLAYER_MARKER_SIZE.get().floatValue();
        g.pose().scale(k * (1f - PLAYER_SQUEEZE * stretch), k * (1f + PLAYER_STRETCH * stretch), 1f);
        g.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.blit(PLAYER_MARKER_ID, -128, -128, 0, 0, 256, 256);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        g.pose().popPose();
    }

    static Button button(int x, int y, int w, String label, Runnable action) {
        return new Button(x, y, w, 20, Component.literal(label), ignored -> action.run(), message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                boolean hover = isHoveredOrFocused();
                g.fill(getX(), getY(), getX() + width, getY() + height, hover ? 0xFF203A39 : 0xBB152226);
                box(g, getX(), getY(), width, height, hover ? GREEN : LINE);
                g.drawCenteredString(Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + 6, hover ? GREEN : TEXT);
            }
        };
    }

    /**
     * 现代战术面板：PANEL_BG 半透明底 + 1px STROKE_DIM 边框 + 顶部 1px 亮线；
     * accentEdge 为 true 时再把左侧边线替换为 2px ACCENT 竖线（y+2 到 y+h-2）。
     * <p>设计取舍：参考图带有小圆角/切角的硬朗观感，但 fill 无法得知面板背后的
     * 背景色，用透明像素"抠角"会因无 alpha 混合而发黑，因此放弃切角方案，
     * 改用"顶部亮线 + 可选左侧强调线"传达同等的现代感。
     */
    static void tacticalPanel(GuiGraphics g, int x, int y, int w, int h, boolean accentEdge) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        box(g, x, y, w, h, STROKE_DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF44546A); // 顶部 1px 亮线（冷灰，比边框亮一档）
        if (accentEdge) g.fill(x, y + 2, x + 2, y + h - 1, ACCENT);
    }

    /**
     * 分区头：左侧 4px 宽 ACCENT 短竖线 (x,y+3)-(x+4,y+11) + tr(key) 标题文字
     * （TEXT 色，位于 x+8）；count 非 null 时在标题文字右侧跟随一枚数量徽标：
     * CHIP_BG 底、宽 = 文字宽 + 8、高 11、y+1，文字 DIM 色。
     */
    static void sectionHeader(GuiGraphics g, int x, int y, String key, String count) {
        g.fill(x, y + 3, x + 4, y + 11, ACCENT);
        String label = tr(key);
        text(g, label, x + 8, y, TEXT);
        if (count != null) {
            var font = Minecraft.getInstance().font;
            int bx = x + 8 + font.width(label) + 6;
            g.fill(bx, y + 1, bx + font.width(count) + 8, y + 12, CHIP_BG);
            text(g, count, bx + 4, y + 3, DIM);
        }
    }

    /**
     * 数字徽标：CHIP_BG 底 + STROKE_DIM 1px 边框 + 居中文字（textColor），
     * 尺寸自适应文字：宽 = 文字宽 + 8，高固定 11。
     */
    static void chip(GuiGraphics g, int x, int y, String text, int textColor) {
        int w = Minecraft.getInstance().font.width(text) + 8;
        g.fill(x, y, x + w, y + 11, CHIP_BG);
        box(g, x, y, w, 11, STROKE_DIM);
        text(g, text, x + 4, y + 2, textColor);
    }

    /**
     * 图层开关行（高 16）：鼠标悬停时整行覆盖 HOVER 增亮；左侧 6×6 状态点
     * （on = iconColor，off = STROKE_DIM）+ label 文字（on = TEXT，off = DIM，
     * 位于 x+14）；右侧 8×5 小滑块：on = ACCENT 底并右移 4px，off = STROKE_DIM
     * 底靠左（背后衬一条 12×5 深色轨道让位移可读）。仅负责绘制，
     * 命中检测由调用方通过 {@link #rowHit} 完成。
     */
    static void toggleRow(GuiGraphics g, int x, int y, int w, String label, boolean on, int mx, int my, int iconColor) {
        if (rowHit(mx, my, x, y, w, 16)) g.fill(x, y, x + w, y + 16, HOVER);
        g.fill(x + 4, y + 5, x + 10, y + 11, on ? iconColor : STROKE_DIM);
        text(g, label, x + 14, y + 4, on ? TEXT : DIM);
        int trackX = x + w - 16, trackY = y + 6;
        g.fill(trackX, trackY, trackX + 12, trackY + 5, PANEL_BG_DEEP);
        int knobX = on ? trackX + 4 : trackX;
        g.fill(knobX, trackY, knobX + 8, trackY + 5, on ? ACCENT : STROKE_DIM);
    }

    /** 命中检测辅助：mx/my 是否落在 (x,y) 起、w×h 的矩形内（含左上边界、不含右下）。 */
    static boolean rowHit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * 18×18 图标按钮（v2.5.0 从 24px 缩小，反馈⑥）：hover 或 active 时底色 0x40253838、
     * 1px ACCENT 边框；常态底色 0x66101820、1px STROKE_DIM 边框。kind 决定内部几何图标
     * （全部以 fill 绘制，视觉中心约在 (x+9, y+9)）：layers = 三条堆叠横线、
     * center = 中心实心点 + 四向十字、floor_up/floor_down = 上下三角、
     * collapse/expand = 左右箭头、settings = 外框 + 中心方块、
     * zoom_in/zoom_out = 中心 ±3 的十字/横线、legend = 列表（三行短横线 + 左端小方点）、
     * hud = 眼睛（中心 4×4 实心 + 上下 8×1 横线 + 左右 1×4 竖线组成眼眶）、
     * close = ×（两条对角斜线）。
     * id 仅供调用方标识（如点击分发），绘制本身不使用。
     */
    static void iconButton(GuiGraphics g, int id, int x, int y, int mx, int my, String kind, boolean active) {
        boolean lit = active || rowHit(mx, my, x, y, 18, 18);
        g.fill(x, y, x + 18, y + 18, lit ? 0x40253838 : 0x66101820);
        box(g, x, y, 18, 18, lit ? ACCENT : STROKE_DIM);
        int main = active ? ACCENT : TEXT;
        switch (kind) {
            case "layers" -> { // 三条 8×3 横线、间隔 1px；最上一条 active 时 ACCENT，其余 DIM
                g.fill(x + 5, y + 3, x + 13, y + 6, active ? ACCENT : DIM);
                g.fill(x + 5, y + 7, x + 13, y + 10, DIM);
                g.fill(x + 5, y + 11, x + 13, y + 14, DIM);
            }
            case "center" -> { // 中心 3×3 实心圆点 + 四向 3px 十字线（各留 1px 间隙）
                g.fill(x + 8, y + 8, x + 11, y + 11, main);
                g.fill(x + 9, y + 3, x + 10, y + 7, DIM);
                g.fill(x + 9, y + 12, x + 10, y + 16, DIM);
                g.fill(x + 3, y + 9, x + 7, y + 10, DIM);
                g.fill(x + 12, y + 9, x + 16, y + 10, DIM);
            }
            case "floor_up" -> { // ▲ 上三角：三行 fill 宽度自上而下递增（3/6/9）
                g.fill(x + 8, y + 6, x + 11, y + 9, main);
                g.fill(x + 6, y + 9, x + 12, y + 12, main);
                g.fill(x + 5, y + 12, x + 14, y + 15, main);
            }
            case "floor_down" -> { // ▼ 下三角：三行 fill 宽度自上而下递减（9/6/3）
                g.fill(x + 5, y + 6, x + 14, y + 9, main);
                g.fill(x + 6, y + 9, x + 12, y + 12, main);
                g.fill(x + 8, y + 12, x + 11, y + 15, main);
            }
            case "collapse" -> { // ◀ 左箭头：中行最左为箭尖
                g.fill(x + 6, y + 5, x + 13, y + 8, main);
                g.fill(x + 3, y + 8, x + 13, y + 11, main);
                g.fill(x + 6, y + 11, x + 13, y + 14, main);
            }
            case "expand" -> { // ▶ 右箭头：中行最右为箭尖
                g.fill(x + 5, y + 5, x + 12, y + 8, main);
                g.fill(x + 5, y + 8, x + 15, y + 11, main);
                g.fill(x + 5, y + 11, x + 12, y + 14, main);
            }
            case "settings" -> { // 外框 8×8 边框 + 中心 4×4 实心方
                box(g, x + 5, y + 5, 8, 8, main);
                g.fill(x + 7, y + 7, x + 11, y + 11, main);
            }
            case "zoom_in" -> { // + 号：中心 (x+9.5,y+9.5) ±3 的 7×1 横线 + 1×7 竖线
                g.fill(x + 6, y + 9, x + 13, y + 10, main);
                g.fill(x + 9, y + 6, x + 10, y + 13, main);
            }
            case "zoom_out" -> { // − 号：仅 7×1 横线
                g.fill(x + 6, y + 9, x + 13, y + 10, main);
            }
            case "legend" -> { // 列表图标：三行“左端 3×3 方点 + 7×2 短横线”，行距 5px
                for (int i = 0; i < 3; i++) {
                    int ry = y + 4 + i * 5;
                    g.fill(x + 3, ry, x + 6, ry + 3, main);
                    g.fill(x + 8, ry + 1, x + 15, ry + 3, main);
                }
            }
            case "hud" -> { // 眼睛图标：中心 4×4 实心瞳 + 上下 8×1 眼眶横线 + 左右 1×4 眼眶竖线
                g.fill(x + 7, y + 7, x + 11, y + 11, main);
                g.fill(x + 5, y + 4, x + 13, y + 5, main);
                g.fill(x + 5, y + 13, x + 13, y + 14, main);
                g.fill(x + 4, y + 7, x + 5, y + 11, main);
                g.fill(x + 13, y + 7, x + 14, y + 11, main);
            }
            case "close" -> { // × 关闭：两条对角斜线，各由四段 2×2 像素阶梯拼成（共 8 段 fill）
                for (int i = 0; i < 4; i++) {
                    g.fill(x + 5 + i * 2, y + 5 + i * 2, x + 7 + i * 2, y + 7 + i * 2, main); // ↘
                    g.fill(x + 11 - i * 2, y + 5 + i * 2, x + 13 - i * 2, y + 7 + i * 2, main); // ↙
                }
            }
            default -> { }
        }
    }

    /**
     * 描边按钮（参考图"重置"样式）：透明底、ACCENT 1px 边框、ACCENT 文字水平居中；
     * 悬停时以 0x2635D8A0（低透明度 ACCENT）填充。高度 h 参数化，常规取 20。
     */
    static void strokeButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        if (rowHit(mx, my, x, y, w, h)) g.fill(x, y, x + w, y + h, 0x2635D8A0);
        box(g, x, y, w, h, ACCENT);
        g.drawCenteredString(Minecraft.getInstance().font, label, x + w / 2, y + (h - 8) / 2, ACCENT);
    }

    /** 坐标格式化：按 ROOT Locale 输出保留 0 位小数的数字字符串（如 "-13"、"2048"）。 */
    static String formatCoord(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }
}
