package dev.draginventory.client.map;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 北向战术地图的运行时状态：视野中心与缩放、动态扫描出的楼层目录、活动楼层。
 *
 * <p><b>v2.5.2：楼层只保留自动模式</b>（反馈⑤：去掉玩家手动调整大地图显示层的功能）。
 * 旧版的手动楼层（manualFloor 锁定 / cycleFloorUp / cycleFloorDown / Home 回自动 /
 * general.auto_floor + manual_floor_y 配置 / 顶栏手动-自动 chip）已全部删除，
 * 活动层恒定跟随玩家所在的楼层。自动层经过三层防抖保证跟得准、不闪烁：
 * <ul>
 *   <li><b>死区</b>：玩家仍在当前层 ±layer_span/2 格内时保持当前层（楼梯中途不抖动）；</li>
 *   <li><b>迟滞</b>：出死区后，候选层必须比当前层更贴近玩家才切换（目录重建期间的
 *       层集合抖动不会引发"等距层来回切"）；</li>
 *   <li><b>稳定层身份</b>：Layer.id = floorY，目录重建时层集合增减不再触发假性换层。</li>
 * </ul>
 * 每次 {@link FactoryMapLayerResolver#discover} 的目录同时写入共享缓存
 * （{@link FactoryMapLayerResolver#sharedCatalog}），小地图 HUD 据此与大地图对齐层。</p>
 *
 * <p>本类不再依赖任何固定工厂范围：
 * <ul>
 *   <li><b>中心钳制</b>：平移/居中只把中心限制在当前楼层目录的扫描范围
 *       ±{@value #CENTER_CLAMP_MARGIN} 格内（目录为空或尚未扫描时不限制），
 *       允许略微出界平移、又不会漂移到无穷远；</li>
 *   <li><b>缩放范围</b>：从配置 behavior.zoom_min / zoom_max 动态读取
 *       （{@link MapConfig#ZOOM_MIN} / {@link MapConfig#ZOOM_MAX}）。
 *       最小缩放不再与屏幕尺寸联动——地图瓦片化后可以看任意远。</li>
 * </ul></p>
 *
 * <p><b>目录重建</b>（{@link #tick}）：每 {@value #RESCAN_INTERVAL_TICKS} 个客户端 tick，
 * 或玩家距上次扫描点水平移动超过 {@value #RESCAN_MOVE_BLOCKS} 格时，
 * 同步重跑 {@link FactoryMapLayerResolver#discover}（半径取自配置 behavior.scan_radius，
 * 采样点数预算已由 resolver 控制）。</p>
 *
 * <p>仅客户端线程使用（由地图屏幕构造并 tick）。</p>
 */
public final class FactoryMapSession implements FactoryMapView {
    /** 重扫描间隔（客户端 tick）：玩家移动时每 40 tick 重建一次楼层目录。 */
    private static final int RESCAN_INTERVAL_TICKS = 40;
    /** 静止玩家降频重扫描间隔（v2.5.1）：10 秒一次。大半径全深度扫描是同步开销
     * （数百毫秒级），静止时每 2 秒重扫只会周期性卡帧；移动时仍维持 40 tick 高频。 */
    private static final int STATIONARY_RESCAN_INTERVAL_TICKS = 200;
    /** 静止判定阈值（格）：距上次扫描点水平移动不足 4 格视为静止。 */
    private static final double STATIONARY_MOVE_BLOCKS = 4.0;
    /** 水平移动重扫描阈值（格）：玩家距上次扫描点水平移动超过 32 格即重建目录。 */
    private static final double RESCAN_MOVE_BLOCKS = 32.0;
    /** 平移钳制余量（格）：视野中心被限制在楼层目录扫描范围 ± 该值内。 */
    private static final double CENTER_CLAMP_MARGIN = 96.0;

    /** 最小缩放（动态值）：读配置 behavior.zoom_min，取代旧的编译期常量。 */
    public static double MIN_ZOOM() { return MapConfig.ZOOM_MIN.get(); }

    /** 最大缩放（动态值）：读配置 behavior.zoom_max。 */
    public static double MAX_ZOOM() { return MapConfig.ZOOM_MAX.get(); }

    private double centerX;
    private double centerZ;
    private double zoom = 1.5;
    private double hoverX;
    private double hoverZ;
    private FactoryMapLayerResolver.LayerCatalog catalog;
    private FactoryMapLayerResolver.Layer activeLayer;
    /** 距上次目录重建经过的 tick 数。 */
    private int ticksSinceScan;
    /** 上次重建时玩家的水平位置，用于"水平移动超过 {@value #RESCAN_MOVE_BLOCKS} 格"的重扫描判定。 */
    private double lastScanX;
    private double lastScanZ;
    /** 最近一次活动层发生变化的毫秒时刻（供 UI 换层闪烁提示）。 */
    private long layerChangedAt;

    /**
     * 创建会话：有玩家与世界时立即居中并做首次目录扫描（活动层随扫描就位）。
     */
    public FactoryMapSession(Minecraft mc) {
        if (mc.player != null && mc.level != null) {
            centerOnPlayer(mc.player);
            rebuild(mc);
        } else {
            catalog = FactoryMapLayerResolver.LayerCatalog.empty();
        }
    }

    /**
     * 每客户端 tick 推进一次（由地图屏幕调用）：
     * <ol>
     *   <li>移动时每 {@value #RESCAN_INTERVAL_TICKS} tick、静止时每
     *       {@value #STATIONARY_RESCAN_INTERVAL_TICKS} tick（v2.5.1 降频），或玩家水平移动
     *       超过 {@value #RESCAN_MOVE_BLOCKS} 格时，同步重建楼层目录并校正活动层
     *       （重扫描点数预算已由 resolver 控制）；</li>
     *   <li>其余 tick 经 {@link #resolveAutoFloor} 带死区防抖+迟滞地解析活动层
     *       （activeLayer 为 null 时也能解析）。</li>
     * </ol>
     */
    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        ticksSinceScan++;
        double dx = mc.player.getX() - lastScanX;
        double dz = mc.player.getZ() - lastScanZ;
        boolean moved = dx * dx + dz * dz > STATIONARY_MOVE_BLOCKS * STATIONARY_MOVE_BLOCKS;
        if (ticksSinceScan >= (moved ? RESCAN_INTERVAL_TICKS : STATIONARY_RESCAN_INTERVAL_TICKS)
                || dx * dx + dz * dz > RESCAN_MOVE_BLOCKS * RESCAN_MOVE_BLOCKS) {
            rebuild(mc);
        } else {
            resolveAutoFloor(mc);
        }
    }

    /**
     * 同步重建楼层目录：半径取配置 behavior.scan_radius。discover 会顺带刷新
     * 共享层目录（小地图对齐层用）。重建后按玩家位置校正活动层（带死区防抖）。
     */
    private void rebuild(Minecraft mc) {
        catalog = FactoryMapLayerResolver.discover(mc.level, mc.player, MapConfig.SCAN_RADIUS.get());
        resolveAutoFloor(mc);
        ticksSinceScan = 0;
        lastScanX = mc.player.getX();
        lastScanZ = mc.player.getZ();
    }

    // ==================== 楼层解析（仅自动） ====================

    /**
     * 自动楼层解析（LAYER_SPAN 死区防抖 + v2.5.1 迟滞）：
     * <ul>
     *   <li>玩家仍在当前层 ±span/2 格死区内时保持当前层，防止楼梯中途上下抖动导致图层闪跳；</li>
     *   <li>出死区后才重新解析，且解析出的候选层必须比当前层更贴近玩家楼层才切换——
     *     目录重建期间的层集合抖动（阈值过滤/区块加载变化使集合增减）不再引发
     *     “等距层之间来回切”，每次切换伴随的瓦片重烤（渐进烘焙下表现为地图闪烁）得以消除。</li>
     * </ul>
     */
    private void resolveAutoFloor(Minecraft mc) {
        int playerFloor = FactoryMapLayerResolver.resolvePlayerFloor(mc.level, mc.player);
        if (activeLayer != null
                && Math.abs(playerFloor - activeLayer.floorY()) <= Math.max(1, MapConfig.LAYER_SPAN.get() / 2)) {
            return; // 玩家仍在当前层死区内：保持当前层
        }
        FactoryMapLayerResolver.Layer candidate = catalog.resolve(mc.level, mc.player);
        if (activeLayer != null && candidate != null
                && Math.abs(candidate.floorY() - playerFloor) >= Math.abs(activeLayer.floorY() - playerFloor)) {
            return; // 迟滞：候选层不比当前层更近，不切换（候选与当前同层时同样保持）
        }
        setActiveLayer(candidate);
    }

    /** 活动层统一写入口：旧值与新层均非 null 且 id 不同时记录换层时刻（供 UI 闪烁）。 */
    private void setActiveLayer(FactoryMapLayerResolver.Layer next) {
        if (activeLayer != null && next != null && next.id() != activeLayer.id()) layerChangedAt = Util.getMillis();
        activeLayer = next;
    }

    // ==================== 视口与坐标 ====================

    /** 构建视口坐标变换（屏幕每帧调用）：先把缩放钳制进配置范围，再钳制中心。 */
    public MapCoordinateTransform transform(int x, int y, int width, int height) {
        zoom = Math.clamp(zoom, minimumZoom(), MAX_ZOOM());
        clampCenter();
        return new MapCoordinateTransform(centerX, centerZ, zoom, x, y, width, height);
    }

    /** 按屏幕像素平移（dx/dy 为像素）；中心被钳制在目录扫描范围 ±{@value #CENTER_CLAMP_MARGIN} 格内。 */
    public void panPixels(double dx, double dy) { centerX -= dx / zoom; centerZ -= dy / zoom; clampCenter(); }

    /** 以屏幕点 (screenX, screenY) 为锚缩放：保证锚点下的世界坐标在缩放前后不变。 */
    public void zoomAt(double screenX, double screenY, double multiplier, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        MapCoordinateTransform before = transform(viewportX, viewportY, viewportWidth, viewportHeight);
        var anchored = before.screenToWorld(screenX, screenY);
        double next = Math.clamp(zoom * multiplier, minimumZoom(), MAX_ZOOM());
        if (Math.abs(next - zoom) < 1.0e-6) return;
        zoom = next;
        centerX = anchored.x() - (screenX - (viewportX + viewportWidth * 0.5)) / zoom;
        centerZ = anchored.z() - (screenY - (viewportY + viewportHeight * 0.5)) / zoom;
        clampCenter();
    }

    /** 居中到玩家当前位置。 */
    public void centerOnPlayer(Player player) { centerX = player.getX(); centerZ = player.getZ(); clampCenter(); }

    /** 居中到指定世界坐标。 */
    public void centerOn(double x, double z) { centerX = x; centerZ = z; clampCenter(); }

    /**
     * 恢复上次视野（follow_player=false 时屏幕跨开关屏记忆用）：
     * 三字段赋值后立即钳制中心（目录在构造时已建好，catalog 为空时钳制为 no-op 不限制）。
     * 缩放的合法范围校验交给后续 transform() 的常规钳制。
     */
    void restore(double cx, double cz, double zoom) {
        centerX = cx;
        centerZ = cz;
        this.zoom = zoom;
        clampCenter();
    }

    /** 当前视野中心 X（只读，屏幕记忆上次中心用）。 */
    public double centerX() { return centerX; }

    /** 当前视野中心 Z（只读，屏幕记忆上次中心用）。 */
    public double centerZ() { return centerZ; }

    /** 更新悬停点（世界坐标；由屏幕鼠标移动驱动）。 */
    public void setHover(double x, double z) { hoverX = x; hoverZ = z; }

    /** 当前缩放（每格方块对应的屏幕像素数）。 */
    public double zoom() { return zoom; }

    /** 悬停点世界 X。 */
    public double hoverX() { return hoverX; }

    /** 悬停点世界 Z。 */
    public double hoverZ() { return hoverZ; }

    /** 悬停点所在方块 X。 */
    public int hoverBlockX() { return Mth.floor(hoverX); }

    /** 悬停点所在方块 Z。 */
    public int hoverBlockZ() { return Mth.floor(hoverZ); }

    // ==================== 楼层查询 ====================

    /** 当前楼层目录（由 tick 重建）。 */
    @Override
    public FactoryMapLayerResolver.LayerCatalog catalog() { return catalog; }

    /** 活动层：恒定跟随玩家所在楼层（自动模式，v2.5.2 起唯一模式）。 */
    @Override
    public FactoryMapLayerResolver.Layer activeLayer() { return activeLayer; }

    /**
     * 地图渲染/过滤实际采用的楼层 Y：活动层 floorY，活动层为 null 时返回 0（既有行为）。
     */
    public int selectedFloorY() {
        return activeLayer == null ? 0 : activeLayer.floorY();
    }

    /** 状态栏文本：未扫描时 "SCANNING"；否则 "Y xx / N"（N 为目录层数）。 */
    public String layerStatus() {
        if (catalog == null || catalog.layers().isEmpty()) return "SCANNING";
        return (activeLayer == null ? "Y ?" : activeLayer.label()) + "  /  " + catalog.layers().size();
    }

    /** 最近一次活动层变化的毫秒时刻（供换层闪烁提示）。 */
    public long layerChangedAt() { return layerChangedAt; }

    // ==================== 内部钳制 ====================

    /** 最小缩放：直接取配置 behavior.zoom_min——瓦片化后可以看任意远，不再与屏幕尺寸联动。 */
    private static double minimumZoom() { return MIN_ZOOM(); }

    /**
     * 把视野中心钳制在当前楼层目录的扫描范围 ±{@value #CENTER_CLAMP_MARGIN} 格内
     * （catalog.minX-96 ~ maxX+96，Z 同理）；目录为 null 或没有任何层时不限制。
     */
    private void clampCenter() {
        if (catalog == null || catalog.layers().isEmpty()) return;
        centerX = Math.clamp(centerX, catalog.minX() - CENTER_CLAMP_MARGIN, catalog.maxX() + CENTER_CLAMP_MARGIN);
        centerZ = Math.clamp(centerZ, catalog.minZ() - CENTER_CLAMP_MARGIN, catalog.maxZ() + CENTER_CLAMP_MARGIN);
    }
}
