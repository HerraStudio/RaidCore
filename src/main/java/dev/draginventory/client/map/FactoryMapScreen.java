package dev.draginventory.client.map;

import static dev.draginventory.client.map.FactoryMapUI.*;

import dev.draginventory.client.TacticalMarker;
import dev.draginventory.client.TacticalMarkerLogic;
import dev.draginventory.client.TacticalMarkerManager;
import java.util.List;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Full-screen operational map, v2.5.0 "clean-screen" layout (feedback ⑤⑥⑦⑧).
 *
 * <p>三角洲风格布局（逻辑坐标 = canvas 空间，由 {@code uiScale} 统一缩放）：
 * <ul>
 *   <li><b>清屏模式</b>（{@link #cleanMode}，由配置 general.clean_on_open 决定开局状态，
 *       v2.5.1 起默认关）：地图满屏，无任何 HUD——只画底幕 scrim（vision.opacity 驱动
 *       透明度，配合 Task 2 的 VOID 全透明瓦片可透出游戏世界）+ 瓦片地图 + 网格 +
 *       连接点 + 标点 + 玩家箭头 + 悬停十字 + 换层提示 + 开场渐隐；右下角小字
 *       完整快捷键说明（v2.5.3：清屏模式也显示全部按键，不再只提示 H 键）；</li>
 *   <li><b>完整 HUD</b>（H 或工具栏眼睛键进入后）：顶部状态条（y 0..24，楼层大字 +
 *       最多 6 枚楼层标签 + N 指示 + 右上 18px 关闭）、右侧 18px 图标
 *       工具栏四键（图例+标点管理弹窗 / 进入清屏 / 居中 / 设置；v2.5.2 删除上层/下层
 *       两键——楼层恒定自动跟随玩家，反馈⑤）、右下角缩放对 + 比例尺 + 坐标读数 +
 *       完整快捷键说明（反馈⑧+反馈⑦：补全 1/2/3 标点类型）；</li>
 *   <li><b>图例 + 标点管理弹窗</b>（反馈⑤ + v2.5.2 反馈③）：工具栏首键开关，
 *       <b>从左侧伸出</b>（面板锚在画布左上，开启时 180ms 左移滑入动画，
 *       位置对标 v2.4.0 的左侧面板但占位更小），三段式——语义图例（2 列 × 4 行 12×12 色块）、
 *       标点类型三行（选中 = 左侧 ACCENT 竖线 + 行底微亮，点击/数字键 1/2/3 切换
 *       MARKER_PING_TYPE）、显示标点开关 + 清除全部（C 键同效）；右键放置当前类型标点
 *       （location/enemy/item 三类型可放置）。</li>
 *   <li><b>操作反馈通知</b>（v2.5.1）：数字键 1/2/3 切换标点类型、C 清除标点等无界面
 *       骤变的操作，底部居中弹出短暂通知（1.4s 渐隐），修复“按了没反应”的观感。</li>
 * </ul>
 * 旧左面板已整体删除，地图恒满屏（无 mapLeft 偏移）。
 * FOLLOW_PLAYER=false 时跨开关屏记忆上次中心与缩放（{@link #lastCenterX}，
 * 旧值为 no-op 的反馈①家族修复）。</p>
 */
public final class FactoryMapScreen extends Screen {
    private final FactoryMapSession session;
    private final FactoryMapTiles terrain = new FactoryMapTiles();
    private boolean dragging;
    /** 清屏模式（开局状态由 general.clean_on_open 决定，v2.5.1 起默认关）：隐藏全部 HUD，只保留地图本体 + 玩家 + 标点。 */
    private boolean cleanMode = MapConfig.CLEAN_ON_OPEN.get();
    /** 图例 + 标点管理弹窗开关（仅完整 HUD 下绘制）。 */
    private boolean popoverOpen;
    /** 弹窗本次开启时刻（v2.5.2 反馈③：从左侧滑入动画用）。 */
    private long popoverOpenedAt;
    /** 弹窗滑入动画时长（毫秒）。 */
    private static final long POPOVER_SLIDE_MS = 180;
    /** 底部操作反馈通知（v2.5.1）：文本与遇期时间戳；null/过期则不画。 */
    private String noticeText;
    private long noticeUntil;
    private float uiScale;
    private int canvasW, canvasH, toolbarX;
    private final long opened = Util.getMillis();
    /** FOLLOW_PLAYER=false 时跨开关屏记忆上次视野（null = 尚无记忆；zoom() 已有）。 */
    private static Double lastCenterX, lastCenterZ, lastZoom;

    private FactoryMapScreen(FactoryMapSession session) {
        super(Component.translatable("draginventory.map.title")); this.session = session;
    }

    /**
     * 创建地图屏幕：FOLLOW_PLAYER=false 且存有上次视野时，在会话构造后恢复
     * （FOLLOW_PLAYER=true 行为不变：开图居中玩家）。
     */
    public static FactoryMapScreen create() {
        FactoryMapSession session = new FactoryMapSession(Minecraft.getInstance());
        if (!MapConfig.FOLLOW_PLAYER.get() && lastCenterX != null && lastCenterZ != null && lastZoom != null) {
            session.restore(lastCenterX, lastCenterZ, lastZoom);
        }
        return new FactoryMapScreen(session);
    }

    /** 只算布局，不挂任何 vanilla widget——按钮全部由 drawChrome 自绘。 */
    @Override protected void init() {
        uiScale = Math.min(1f, Math.min(width / 640f, height / 360f));
        canvasW = Math.round(width / uiScale); canvasH = Math.round(height / uiScale);
        toolbarX = canvasW - 26; // 18px 图标 + 8px 右边距（反馈⑥：按钮缩小）
        dragging = false;
    }
    @Override public void tick() {
        if (minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()) { onClose(); return; }
        session.tick(minecraft);
    }
    private void center() { if (minecraft.player != null) session.centerOnPlayer(minecraft.player); }
    private MapCoordinateTransform transform() { return session.transform(0, 0, canvasW, canvasH); }

    // ==================== HUD 遮挡判定 ====================

    /** UI 遮挡判定：clean 恒 false（全屏地图）；完整 HUD 下 = 顶栏 / 右侧工具条 / 弹窗矩形。 */
    private boolean overPanel(double x, double y) {
        if (cleanMode) return false;
        if (y < 24) return true;                                    // 顶部状态条
        if (x >= canvasW - 28 && y >= 30) return true;              // 右侧整条（工具栏 + 缩放 + 读数）
        if (popoverOpen) {
            PopoverLayout L = popoverLayout();
            return rowHit((int) x, (int) y, L.x, L.y, L.w, L.h);    // 弹窗整体拦截
        }
        return false;
    }

    // ==================== 渲染 ====================

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (minecraft.level == null || minecraft.player == null) return;
        int mx = (int) (mouseX / uiScale), my = (int) (mouseY / uiScale);
        g.pose().pushPose(); g.pose().scale(uiScale, uiScale, 1);
        // 底幕透明度 = vision.opacity（反馈①：低不透明度可透出游戏世界，Task 2 VOID 已改全透明）。
        int scrim = (int) (MapConfig.OPACITY.get() * 255) << 24 | 0x09151B;
        g.fill(0, 0, canvasW, canvasH, scrim);
        var t = transform();
        g.enableScissor(0, 0, canvasW, canvasH);
        terrain.draw(g, minecraft, t, session);
        var active = session.activeLayer();
        if (active != null) drawConnectors(g, t, active.connectors());
        drawGrid(g, t);
        g.disableScissor();
        if (!cleanMode) { // 暗角装帧（清屏模式不画）
            g.fillGradient(0, 0, canvasW, 70, 0xF209141A, 0x0009141A);
            g.fillGradient(0, canvasH - 75, canvasW, canvasH, 0x0009141A, 0xF209141A);
            for (int i = 0; i < 24; i++) {
                int alpha = (int) (130 * Math.pow(1 - i / 24.0, 2));
                g.fill(i * 4, 24, i * 4 + 4, canvasH - 30, alpha << 24 | 0x0A2029);
                g.fill(canvasW - i * 4 - 4, 24, canvasW - i * 4, canvasH - 30, alpha << 24 | 0x081319);
            }
        }
        var markers = TacticalMarkerManager.snapshot(partial).stream()
                .filter(marker -> session.activeLayer() != null
                        && Math.abs(marker.position(partial).y - 1 - session.selectedFloorY()) <= 3)
                .toList();
        drawMarkers(g, t, markers, partial);
        // v2.5.7 动效优化：partialTick 插值坐标喂 FX + 亚像素绘制（去 Mth.floor 台阶）；
        // 边界判定（inMapBody/contains）仍用整数像素/方块坐标，语义不变。
        var ipos = minecraft.player.getPosition(partial);
        float pfx = (float) t.worldToScreenX(ipos.x), pfy = (float) t.worldToScreenY(ipos.z);
        if (inMapBody(Mth.floor(pfx), Mth.floor(pfy)) && session.activeLayer() != null
                && session.activeLayer().contains(Mth.floor(ipos.x), Mth.floor(ipos.z))) {
            // v2.5.6：去掉玩家周围 21×21 半透明绿框（用户反馈"多余的小方框"）；
            // v2.5.7：玩家图标换导航箭头（用户 SVG），接入动效（与小地图同源）：
            // 尾迹残影垫底 + 平滑转向 + 弹性拉伸。
            PlayerMarkerFX fx = PlayerMarkerFX.INSTANCE;
            fx.update(ipos.x, ipos.z, minecraft.player.getYRot());
            for (var s : fx.trail()) {
                float tx = (float) t.worldToScreenX(s.x()), ty = (float) t.worldToScreenY(s.z());
                if (inMapBody(Mth.floor(tx), Mth.floor(ty))) {
                    player(g, tx, ty, s.yaw(), s.alpha(), fx.stretch(), s.scale());
                }
            }
            player(g, pfx, pfy, fx.smoothYaw(), 1f, fx.stretch(), fx.drawScale(Util.getMillis()));
        }
        if (!overPanel(mx, my)) {
            var world = t.screenToWorld(mx, my); session.setHover(world.x(), world.z());
            g.fill(mx - 7, my, mx - 2, my + 1, 0x8899B7B4); g.fill(mx + 3, my, mx + 8, my + 1, 0x8899B7B4);
            g.fill(mx, my - 7, mx + 1, my - 2, 0x8899B7B4); g.fill(mx, my + 3, mx + 1, my + 8, 0x8899B7B4);
        }
        if (cleanMode) drawCleanHint(g); else drawChrome(g, mx, my);
        drawNotice(g);
        long layerFlash = Util.getMillis() - session.layerChangedAt();
        if (session.layerChangedAt() > 0 && layerFlash < 1600) {
            int alpha = (int) (220 * (1.0 - layerFlash / 1600.0));
            String notice = tr("layer_changed") + "  " + session.layerStatus();
            int nw = font.width(notice) + 24;
            int nx = (canvasW - nw) / 2;
            g.fill(nx, 48, nx + nw, 72, alpha << 24 | 0x102C32);
            box(g, nx, 48, nw, 24, alpha << 24 | GREEN);
            text(g, notice, nx + 12, 56, alpha << 24 | TEXT);
        }
        super.render(g, mx, my, partial);
        float fade = 1 - (float) Math.clamp((Util.getMillis() - opened) / 220.0, 0, 1);
        if (fade > 0) g.fill(0, 0, canvasW, canvasH, (int) (fade * fade * 210) << 24 | 0x081217);
        g.pose().popPose();
    }

    /** 网格线：clean 满屏；完整 HUD 下避开顶栏与右侧工具条。 */
    private void drawGrid(GuiGraphics g, MapCoordinateTransform t) {
        if (!MapConfig.GRID.get()) return;
        int spacing = 16; while (spacing * t.zoom() < 64) spacing *= 2;
        int top = cleanMode ? 0 : 24, bottom = cleanMode ? canvasH : canvasH - 30;
        int right = cleanMode ? canvasW : canvasW - 30;
        var min = t.screenToWorld(0, 0); var max = t.screenToWorld(canvasW, canvasH);
        for (int x = Math.floorDiv(Mth.floor(min.x()), spacing) * spacing; x < max.x(); x += spacing) {
            int sx = Mth.floor(t.worldToScreenX(x)); g.fill(sx, top, sx + 1, bottom, 0x123F6B70);
            if (sx > 8 && sx < canvasW - 34) text(g, Integer.toString(x), sx + 3, top + 6, 0xFF58716F);
        }
        for (int z = Math.floorDiv(Mth.floor(min.z()), spacing) * spacing; z < max.z(); z += spacing) {
            int sy = Mth.floor(t.worldToScreenY(z)); g.fill(0, sy, right, sy + 1, 0x123F6B70);
        }
    }

    // Inter-floor connectors of the active layer: gold box = STAIR, cyan box = ELEVATOR
    // (dark backing ring + colored 7×7 frame + dark core, inlined from the retired FactoryMapTexture).
    private void drawConnectors(GuiGraphics g, MapCoordinateTransform t, List<FactoryMapLayerResolver.Connector> connectors) {
        for (var connector : connectors) {
            int x = Mth.floor(t.worldToScreenX(connector.x()));
            int y = Mth.floor(t.worldToScreenY(connector.z()));
            int color = connector.kind() == FactoryMapLayerResolver.Connector.Kind.STAIR ? 0xFFE9B75A : 0xFF62D8C6;
            g.fill(x - 3, y - 3, x + 4, y + 4, 0xAA0A1519);
            g.fill(x - 2, y - 2, x + 3, y + 3, color);
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF0B171B);
        }
    }

    /**
     * 标点绘制（反馈⑤三类型 + 反馈①MARKER_STYLE 接线）：
     * 可见性 = markers.visible 配置 + 楼层 ±3；
     * 风格 minimal = 中心 3×3 实心 + 外圈 7×7 描边小圆点；tactical（默认/非法值）= 既有 glyph 动画。
     */
    private void drawMarkers(GuiGraphics g, MapCoordinateTransform t, List<TacticalMarker> markers, float partial) {
        if (!MapConfig.MARKERS_VISIBLE.get()) return;
        boolean minimal = "minimal".equals(MapConfig.MARKER_STYLE.get());
        int index = 0;
        for (var marker : markers) {
            index++;
            Vec3 pos = marker.position(partial);
            if (session.activeLayer() == null || Math.abs(pos.y - 1 - session.selectedFloorY()) > 3) continue;
            int x = Mth.floor(t.worldToScreenX(pos.x)), y = Mth.floor(t.worldToScreenY(pos.z));
            if (!inMapBody(x, y)) continue;
            int color = color(marker.type());
            if (minimal) {
                box(g, x - 3, y - 3, 7, 7, color);          // 外圈 7×7 描边
                g.fill(x - 1, y - 1, x + 2, y + 2, color); // 中心 3×3 实心
            } else {
                var animation = TacticalMarkerLogic.appearance(Util.getMillis() - marker.createdAt());
                g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(animation.scale(), animation.scale(), 1);
                glyph(g, 0, 0, marker.type(), color); g.pose().popPose();
            }
            text(g, String.format(Locale.ROOT, "%02d", index), x + 9, y - 6, color);
            String d = meters(pos) + "m"; text(g, d, x - font.width(d) / 2, y + 12, TEXT);
        }
    }

    private int meters(Vec3 p) { return (int) Math.floor(p.distanceTo(minecraft.player.position())); }

    /** 地图主体可视区（标点/玩家箭头避开 HUD）：clean 近满屏；完整 HUD 避开顶栏与右下簇。 */
    private boolean inMapBody(int x, int y) {
        if (cleanMode) return x > 8 && x < canvasW - 8 && y > 8 && y < canvasH - 22;
        return x > 8 && x < canvasW - 34 && y > 26 && y < canvasH - 64;
    }

    // ==================== HUD（完整界面，非 clean） ====================

    private void drawChrome(GuiGraphics g, int mx, int my) {
        drawTopBar(g, mx, my);
        drawToolbar(g, mx, my);
        drawBottomRight(g, mx, my);
        drawFooter(g);
        if (popoverOpen) drawPopover(g, mx, my);
    }

    /**
     * 右下角完整快捷键说明（v2.5.3 重做）：清屏与完整界面共用同一条 controls_full
     * （反馈“右下角快捷键说明提示不全”——清屏模式此前只提示 H 键，且完整模式缺中键标点）。
     * 字号 0.7→0.6，写全全部操作：M/ESC 关闭、左键拖动、右/中键标点、滚轮缩放、
     * 空格居中、1/2/3 标点类型、H 界面开关、C 清除标点；两个动态键名取自当前绑定。
     */
    private void drawControlsHint(GuiGraphics g, int color) {
        String hints = tr("controls_full", FactoryMapKeyBindings.TOGGLE_MAP_HUD.getTranslatedKeyMessage().getString(),
                FactoryMapKeyBindings.CLEAR_MARKERS.getTranslatedKeyMessage().getString());
        smallText(g, hints, canvasW - 32 - textWidth(hints, 0.6f), canvasH - 14, color, 0.6f);
    }

    /** 清屏模式右下角提示：完整快捷键说明，0.6 缩放、DIM 60% 透明度（保持清屏克制感）。 */
    private void drawCleanHint(GuiGraphics g) {
        drawControlsHint(g, 0x99829594);
    }

    /**
     * 底部居中操作反馈通知（v2.5.1）：数字键切标点类型 / C 清除等操作的可见响应。
     * 显示 1.4s，末尾 400ms 线性渐隐；与顶部的换层提示不冲突。
     */
    private void drawNotice(GuiGraphics g) {
        long now = Util.getMillis();
        if (noticeText == null || now >= noticeUntil) return;
        int alpha = (int) (220 * Math.clamp((noticeUntil - now) / 400.0, 0, 1));
        int nw = font.width(noticeText) + 24;
        int nx = (canvasW - nw) / 2;
        int ny = canvasH - 46;
        g.fill(nx, ny, nx + nw, ny + 24, alpha << 24 | 0x102C32);
        box(g, nx, ny, nw, 24, alpha << 24 | GREEN);
        text(g, noticeText, nx + 12, ny + 8, alpha << 24 | TEXT);
    }

    /** 弹出操作反馈通知（1.4s 后渐隐）。 */
    private void notice(String key, Object... args) {
        noticeText = tr(key, args);
        noticeUntil = Util.getMillis() + 1400;
    }

    /** 顶部状态条（y 0..24）：楼层大字 + 楼层标签组（最多 6 枚，活动层高亮）+ 北向指示 + 关闭。 */
    private void drawTopBar(GuiGraphics g, int mx, int my) {
        int x = 14;
        String floor = "Y " + session.selectedFloorY();
        text(g, floor, x, 8, ACCENT);
        x += font.width(floor) + 12;
        var catalog = session.catalog();
        if (catalog != null) {
            int activeFloorY = session.activeLayer() == null ? Integer.MIN_VALUE : session.activeLayer().floorY();
            int shown = 0;
            for (var layer : catalog.layers()) {
                if (shown++ == 6) break;
                String label = "Y" + layer.floorY();
                if (layer.floorY() == activeFloorY) chip(g, x, 6, label, ACCENT);
                else text(g, label, x + 4, 8, DIM);
                x += font.width(label) + 10; // chip 宽（文字+8）+ 间隔 2
            }
        }
        int cx = canvasW / 2;
        text(g, "N", cx - 2, 6, GREEN);
        g.fill(cx - 1, 15, cx, 21, GREEN);
        iconButton(g, 8, toolbarX, 3, mx, my, "close", false);
    }

    /** 右侧图标工具栏（x=canvasW-26，18px、间距 22，自 y=34）：图例弹窗 / 清屏 / 居中 / 设置。
     * v2.5.2 删除上层/下层两键（反馈⑤：楼层恒定自动跟随玩家）。 */
    private void drawToolbar(GuiGraphics g, int mx, int my) {
        iconButton(g, 1, toolbarX, 34, mx, my, "legend", popoverOpen);
        iconButton(g, 2, toolbarX, 56, mx, my, "hud", false);
        iconButton(g, 3, toolbarX, 78, mx, my, "center", false);
        iconButton(g, 6, toolbarX, 100, mx, my, "settings", false);
    }

    /** 右下角：缩放按钮对 + 比例尺（右对齐至 canvasW-32）+ 坐标读数。 */
    private void drawBottomRight(GuiGraphics g, int mx, int my) {
        iconButton(g, 7, toolbarX, canvasH - 96, mx, my, "zoom_in", false);
        iconButton(g, 9, toolbarX, canvasH - 74, mx, my, "zoom_out", false);
        int blocks = 16;
        while (blocks * session.zoom() < 36) blocks *= 2;
        while (blocks * session.zoom() > 95 && blocks > 1) blocks /= 2;
        int pixels = (int) (blocks * session.zoom()), right = canvasW - 32, by = canvasH - 58;
        g.fill(right - pixels, by, right, by + 1, DIM);
        g.fill(right - pixels, by - 3, right - pixels + 1, by + 2, DIM);
        g.fill(right - 1, by - 3, right, by + 2, DIM);
        text(g, blocks + "m", right - font.width(blocks + "m"), by - 12, TEXT);
        String coords = "X " + formatCoord(session.hoverBlockX()) + "  Z " + formatCoord(session.hoverBlockZ())
                + "  ·  " + Math.round(session.zoom() / 1.5 * 100) + "%";
        text(g, coords, right - font.width(coords), canvasH - 44, DIM);
    }

    /** 底部提示条（反馈⑧）：左 = 本地情报说明；右 = 完整操作说明（v2.5.3 与清屏模式同源，0.6 缩放）。 */
    private void drawFooter(GuiGraphics g) {
        smallText(g, tr("loaded_only"), 14, canvasH - 14, DIM, 0.7f);
        drawControlsHint(g, TEXT);
    }

    // ==================== 图例 + 标点管理弹窗（反馈⑤） ====================

    /** 弹窗几何快照（绘制与命中共用，一处定义避免漂移）。 */
    private record PopoverLayout(int x, int y, int w, int h, int legendRowH, int typeRowH,
            int headerAY, int legendY, int headerBY, int typeY, int toggleY, int clearY, int hintY) {}

    /**
     * 弹窗几何（v2.5.2 反馈③：从左侧伸出）：宽 210、x = 8（左边距）、y = 34；
     * 标准高度 240（最小画布 640×360 下 34+240=274 ≤ canvasH-40=320，不溢出）；
     * 超出 canvasH-40 时压缩图例行距与类型行高（保险路径，常规几何不触发）。
     * 占位比对标 v2.4.0 的左侧全高面板小：仅 210×240 浮出面板，地图仍近乎满屏。
     */
    private PopoverLayout popoverLayout() {
        int w = 210, x = 8, y = 34;
        int legendRowH = 18, typeRowH = 20;
        int headerAY = y + 10, legendY = y + 26, headerBY = y + 104;
        int typeY = y + 118, toggleY = y + 180, clearY = y + 200, hintY = y + 224;
        int h = 240;
        if (y + h > canvasH - 40) { // 压缩图例行距（4×16=64）与类型行高（3×18=54）
            legendRowH = 16; typeRowH = 18;
            headerBY = y + 96; typeY = y + 110; toggleY = y + 168; clearY = y + 188; hintY = y + 210;
            h = 226;
        }
        return new PopoverLayout(x, y, w, h, legendRowH, typeRowH, headerAY, legendY, headerBY, typeY, toggleY, clearY, hintY);
    }

    /** 弹窗滑入进度（0..1，开窗后 {@value #POPOVER_SLIDE_MS}ms 内从左侧滑入，ease-out）。 */
    private float popoverSlide() {
        long t = Util.getMillis() - popoverOpenedAt;
        if (t >= POPOVER_SLIDE_MS) return 1;
        float p = t / (float) POPOVER_SLIDE_MS;
        return 1 - (1 - p) * (1 - p) * (1 - p);  // ease-out cubic
    }

    /** 弹窗内容三段：a) 语义图例 2 列 × 4 行；b) 标点类型三行 + 显示标点 + 清除全部；c) 底部小字提示。 */
    private void drawPopover(GuiGraphics g, int mx, int my) {
        PopoverLayout L = popoverLayout();
        // 从左侧伸出（反馈③）：开启后 180ms 内从画布左侧滑入到位。
        g.pose().pushPose();
        g.pose().translate(-(1 - popoverSlide()) * (L.w + 20), 0, 0);
        tacticalPanel(g, L.x, L.y, L.w, L.h, true);
        var all = TacticalMarkerManager.snapshot(1);
        // a) 地图元素：2 列 × 4 行，每行 = 12×12 色块（语义基础色）+ 名称。
        sectionHeader(g, L.x + 10, L.headerAY, "legend_section", null);
        MapSemantics[] legend = {MapSemantics.WALL, MapSemantics.FLOOR, MapSemantics.EDGE, MapSemantics.STAIR,
                MapSemantics.DOOR, MapSemantics.WATER, MapSemantics.BLOCKED, MapSemantics.DECOR};
        for (int i = 0; i < legend.length; i++) {
            int col = i / 4, row = i % 4;
            int sx = L.x + 10 + col * 99, sy = L.legendY + row * L.legendRowH;
            g.fill(sx, sy + 3, sx + 12, sy + 15, FactoryMapPalette.baseColor(legend[i]));
            box(g, sx, sy + 3, 12, 12, STROKE_DIM);
            text(g, tr("sem_" + legend[i].name().toLowerCase(Locale.ROOT)), sx + 16, sy + 6, TEXT);
        }
        // b) 标点管理：类型三行（选中 = 行底微亮 + 左侧 2px ACCENT 竖线）+ 数量徽标。
        sectionHeader(g, L.x + 10, L.headerBY, "marker_section", null);
        TacticalMarker.Type[] types = TacticalMarker.Type.values();
        for (int i = 0; i < types.length && i < 3; i++) {
            TacticalMarker.Type type = types[i];
            int ry = L.typeY + i * L.typeRowH;
            boolean selected = pingType() == type;
            if (selected) g.fill(L.x + 10, ry, L.x + 200, ry + L.typeRowH, 0x1435D8A0);
            else if (rowHit(mx, my, L.x + 10, ry, 190, L.typeRowH)) g.fill(L.x + 10, ry, L.x + 200, ry + L.typeRowH, HOVER);
            if (selected) g.fill(L.x + 10, ry, L.x + 12, ry + L.typeRowH, ACCENT);
            g.pose().pushPose();
            g.pose().translate(L.x + 26, ry + L.typeRowH / 2, 0);
            g.pose().scale(.75f, .75f, 1);
            glyph(g, 0, 0, type, color(type));
            g.pose().popPose();
            text(g, tr(type.name().toLowerCase(Locale.ROOT)), L.x + 38, ry + L.typeRowH / 2 - 4, TEXT);
            int count = (int) all.stream().filter(m -> m.type() == type).count();
            String cnt = "×" + count;
            if (selected) { // 选中行右侧小徽标"当前"
                String cur = tr("current");
                text(g, cur, L.x + 200 - font.width(cnt) - font.width(cur) - 8, ry + L.typeRowH / 2 - 4, ACCENT);
            }
            text(g, cnt, L.x + 200 - font.width(cnt), ry + L.typeRowH / 2 - 4, DIM);
        }
        toggleRow(g, L.x + 10, L.toggleY, 190, tr("markers_show"), MapConfig.MARKERS_VISIBLE.get(), mx, my, ACCENT);
        strokeButton(g, L.x + 10, L.clearY, 190, 18, tr("clear_all"), mx, my);
        // c) 底部小字提示（0.75 缩放）。
        smallText(g, tr("ping_hint"), L.x + 10, L.hintY, DIM, 0.75f);
        g.pose().popPose();
    }

    // ==================== 标点类型解析 ====================

    /** 当前放置类型（markers.ping_type）：location/enemy/item，非法值回退 LOCATION。 */
    private static TacticalMarker.Type pingType() {
        String id = MapConfig.MARKER_PING_TYPE.get();
        if (id == null) return TacticalMarker.Type.LOCATION;
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "enemy" -> TacticalMarker.Type.ENEMY;
            case "item" -> TacticalMarker.Type.ITEM;
            default -> TacticalMarker.Type.LOCATION;
        };
    }

    /** 类型 → 配置 id（与 MapConfig.MARKER_PING_TYPE 注释同口径）。 */
    private static String pingTypeId(TacticalMarker.Type type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    // ==================== 交互（HUD 控件命中 + 地图拖动/标点） ====================

    @Override public boolean mouseClicked(double x, double y, int button) {
        x /= uiScale; y /= uiScale;
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && clickChrome(x, y)) return true;
        if (overPanel(x, y)) return false;
        if (button == 0) { dragging = true; return true; }
        if (button == 1 || button == 2) { placeMapPing(transform(), x, y); return true; }
        return false;
    }

    /**
     * HUD 自绘控件左键命中分发（canvas 逻辑坐标；矩形与 drawChrome 各组件逐一对齐）。
     * clean 模式直接返回 false（全部交给地图拖动/标点）；
     * 优先级：顶栏关闭 → 工具栏 4 键 → 缩放对 → 弹窗（类型三行/显示标点/清除，空白拦截）。
     */
    private boolean clickChrome(double x, double y) {
        if (cleanMode) return false;
        int mx = (int) x, my = (int) y;
        if (rowHit(mx, my, toolbarX, 3, 18, 18)) { onClose(); return true; } // a) 顶栏关闭
        if (rowHit(mx, my, toolbarX, 34, 18, 18)) { // b) legend：开弹窗（记录开启时刻供滑入动画）
            popoverOpen = !popoverOpen;
            if (popoverOpen) popoverOpenedAt = Util.getMillis();
            return true;
        }
        if (rowHit(mx, my, toolbarX, 56, 18, 18)) { cleanMode = true; return true; } // b) hud：进入清屏
        if (rowHit(mx, my, toolbarX, 78, 18, 18)) { center(); return true; } // b) center
        if (rowHit(mx, my, toolbarX, 100, 18, 18)) { // b) settings
            minecraft.setScreen(MapSettingsScreen.create()); return true;
        }
        if (rowHit(mx, my, toolbarX, canvasH - 96, 18, 18)) { // c) zoom_in：以画布中心为锚
            session.zoomAt(canvasW / 2.0, canvasH / 2.0, 1.5, 0, 0, canvasW, canvasH); return true;
        }
        if (rowHit(mx, my, toolbarX, canvasH - 74, 18, 18)) { // c) zoom_out
            session.zoomAt(canvasW / 2.0, canvasH / 2.0, 1 / 1.5, 0, 0, canvasW, canvasH); return true;
        }
        if (popoverOpen) { // d) 弹窗内容 + 空白整体拦截（点空白不落到地图）
            PopoverLayout L = popoverLayout();
            if (rowHit(mx, my, L.x, L.y, L.w, L.h)) {
                TacticalMarker.Type[] types = TacticalMarker.Type.values();
                for (int i = 0; i < types.length && i < 3; i++) {
                    if (rowHit(mx, my, L.x + 10, L.typeY + i * L.typeRowH(), 190, L.typeRowH())) {
                        MapConfig.set(MapConfig.MARKER_PING_TYPE, pingTypeId(types[i])); return true;
                    }
                }
                if (rowHit(mx, my, L.x + 10, L.toggleY, 190, 16)) {
                    MapConfig.set(MapConfig.MARKERS_VISIBLE, !MapConfig.MARKERS_VISIBLE.get()); return true;
                }
                if (rowHit(mx, my, L.x + 10, L.clearY, 190, 18)) {
                    TacticalMarkerManager.clearAllMarkers(); return true;
                }
                return true;
            }
        }
        return false;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging) { dragging = false; return true; }
        return super.mouseReleased(x / uiScale, y / uiScale, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && dragging) { session.panPixels(dx / uiScale, dy / uiScale); return true; }
        return super.mouseDragged(x / uiScale, y / uiScale, button, dx / uiScale, dy / uiScale);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        x /= uiScale; y /= uiScale;
        if (!overPanel(x, y) && sy != 0) { session.zoomAt(x, y, Math.pow(1.18, sy), 0, 0, canvasW, canvasH); return true; }
        return super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (FactoryMapKeyBindings.OPEN_MAP.matches(key, scan)) { onClose(); return true; }
        if (FactoryMapKeyBindings.TOGGLE_MAP_HUD.matches(key, scan)) { cleanMode = !cleanMode; return true; } // H：任何模式可切
        if (FactoryMapKeyBindings.CLEAR_MARKERS.matches(key, scan)) { clearMarkersWithNotice(); return true; }
        // v2.5.2（反馈④⑦）：全部快捷键均为主键盘核心键区可打出的键——M/C/H/1/2/3/空格，
        // 不依赖数字小键盘、PageUp/PageDown/Home 等扩展键区（60% 小配列键盘友好）。
        // 手动调层键（PgUp/PgDn/Home）已随手动楼层功能一并移除（反馈⑤）。
        if (key == GLFW.GLFW_KEY_1) { selectPingTypeWithNotice("location"); return true; }
        if (key == GLFW.GLFW_KEY_2) { selectPingTypeWithNotice("enemy"); return true; }
        if (key == GLFW.GLFW_KEY_3) { selectPingTypeWithNotice("item"); return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { center(); return true; }
        return super.keyPressed(key, scan, modifiers); // ESC 走 Screen 默认 onClose
    }

    /** 切换右键标点类型并弹出反馈通知（v2.5.1：修复“按 1/2/3 无任何可见响应”）。 */
    private void selectPingTypeWithNotice(String id) {
        MapConfig.set(MapConfig.MARKER_PING_TYPE, id);
        notice("ping_notice", tr(id));
    }

    /** 清除全部地图标点并弹出反馈通知；无标点时提示“没有标点”。 */
    private void clearMarkersWithNotice() {
        var all = TacticalMarkerManager.snapshot(1);
        if (all.isEmpty()) { notice("no_markers_notice"); return; }
        TacticalMarkerManager.clearAllMarkers();
        notice("cleared_notice", all.size());
    }
    private void placeMapPing(MapCoordinateTransform t, double x, double y) {
        var world = t.screenToWorld(x, y); int wx = Mth.floor(world.x()), wz = Mth.floor(world.z());
        if (!minecraft.level.hasChunkAt(new BlockPos(wx, 0, wz))) return;
        int floor = session.selectedFloorY();
        if (floor == FactoryMapLayerResolver.NO_FLOOR || floor < minecraft.level.getMinBuildHeight()) return;
        TacticalMarkerManager.placeMapMarker(pingType(), new Vec3(world.x(), floor + 1.05, world.z()));
    }
    @Override public boolean isPauseScreen() { return false; }
    // The map owns its backdrop; vanilla's menu blur would blur the map and labels just drawn.
    @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {}
    @Override public void removed() {
        rememberView(); // FOLLOW_PLAYER 记忆（含经设置界面中转的路径）
        terrain.close();
        super.removed();
    }
    /** 存回当前视野（follow_player=false 时下次开图恢复；中转设置界面同样生效）。 */
    private void rememberView() {
        lastCenterX = session.centerX();
        lastCenterZ = session.centerZ();
        lastZoom = session.zoom();
    }
}
