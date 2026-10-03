package dev.draginventory.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Local-only pings: camera raycast, click disambiguation and world-scoped state.
 *
 * <p>v2.5.7 联动扩展：新增<b>外部标点池</b>（{@link #placeExternalMarker}，稳定 ID +
 * 自定义 TTL + 独立 16 名额，不占玩家手动 5 名额）与<b>监听器</b>（{@link #addMarkerListener}，
 * 增删过期清除事件推送）。两池经 {@link #allMarkers()} 合并渲染：大地图/小地图
 * （snapshot）/世界 HUD/方位条桥接自动同步，单一数据源不变。</p>
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class TacticalMarkerManager {
    static final double MAX_DISTANCE = 128;
    static final SequencedMap<Object, TacticalMarker> MARKERS = new LinkedHashMap<>();

    /** 外部联动标点独立容量（不占玩家手动 5 名额；超量按插入序逐出最旧）。 */
    public static final int MAX_EXTERNAL_MARKERS = 16;
    /** 外部标点 TTL 上限（24 小时，与对局计时同口径）。 */
    public static final long MAX_EXTERNAL_TTL_MS = 86_400_000L;

    /** 外部标点存储：稳定 ID → 标点（独立于玩家 MARKERS 的 FIFO 名额池，互不挤占）。 */
    static final SequencedMap<String, TacticalMarker> EXTERNAL = new LinkedHashMap<>();

    /** 联动监听器（CopyOnWrite：任意线程注册/注销，客户端线程回调，异常隔离）。 */
    private static final List<TacticalMarkerListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final TacticalClickGesture<TargetHit> CLICKS = new TacticalClickGesture<>();
    private static TacticalMarkerLogic.MarkerWrite<Object,TacticalMarker> firstWrite;
    private static ClientLevel level;
    private static Player owner;

    private TacticalMarkerManager() {}

    @SubscribeEvent
    public static void input(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        maintain(mc, Util.getMillis());
        if (!canInput(mc) || !mc.mouseHandler.isMouseGrabbed()) return;
        // Release still reaches vanilla so a pre-existing held binding cannot stick.
        event.setCanceled(true);
        CLICKS.press(raycast(mc), Util.getMillis(), TacticalMarkerManager::mark);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        maintain(mc, Util.getMillis());
        if (canInput(mc)) {
            CLICKS.flush(Util.getMillis());
            if (!CLICKS.waiting()) firstWrite = null;
        } else cancelClicks();
    }

    private static void cancelClicks() { CLICKS.cancel(); firstWrite = null; }

    @SubscribeEvent
    public static void screenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null) cancelClicks();
    }

    static void maintain(Minecraft mc, long now) {
        if (level != mc.level || owner != mc.player) {
            for (TacticalMarker marker : MARKERS.values()) fire(TacticalMarkerListener.Cause.CLEARED, marker);
            for (TacticalMarker marker : EXTERNAL.values()) fire(TacticalMarkerListener.Cause.CLEARED, marker);
            MARKERS.clear();
            EXTERNAL.clear();
            cancelClicks();
            level = mc.level;
            owner = mc.player;
            TacticalMarkerHud.invalidate();
        }
        // Also called before rendering so pickups disappear on the next frame, even in menus.
        pruneExpired(MARKERS, mc, now);
        pruneExpired(EXTERNAL, mc, now);
    }

    /** 逐表过期清理：先收集再移除再触发事件（避免事件回调重入时并发修改）。 */
    private static void pruneExpired(SequencedMap<?, TacticalMarker> markers, Minecraft mc, long now) {
        List<TacticalMarker> expired = null;
        for (TacticalMarker marker : markers.values()) {
            if (!marker.valid(mc.level, now)) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(marker);
            }
        }
        if (expired == null) return;
        for (TacticalMarker marker : expired) {
            markers.values().remove(marker);
            fire(TacticalMarkerListener.Cause.EXPIRED, marker);
        }
    }

    static boolean canInput(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.getCameraEntity() == mc.player && mc.screen == null && mc.getOverlay() == null
                && !mc.isPaused() && !mc.options.hideGui;
    }

    // ==================== v2.5.7 联动扩展：监听器 / 合并视图 / 外部标点 ====================

    /** 注册联动监听器（任意线程；客户端线程回调；传 null 忽略）。 */
    public static void addMarkerListener(TacticalMarkerListener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** 注销联动监听器（幂等）。 */
    public static void removeMarkerListener(TacticalMarkerListener listener) { LISTENERS.remove(listener); }

    /** 事件分发：逐监听器隔离异常（联动方拖不垮标点系统）。 */
    private static void fire(TacticalMarkerListener.Cause cause, TacticalMarker marker) {
        if (marker == null || LISTENERS.isEmpty()) return;
        for (TacticalMarkerListener listener : LISTENERS) {
            try {
                listener.onMarkerEvent(cause, marker);
            } catch (RuntimeException ignored) {
                // 单个联动方异常不影响其余监听器与标点系统本体。
            }
        }
    }

    /** 两池是否都为空（世界 HUD / 方位条渲染短路用）。 */
    static boolean hasNoMarkers() { return MARKERS.isEmpty() && EXTERNAL.isEmpty(); }

    /** 全量标点视图（玩家手动在前 + 外部联动在后）：世界 HUD / 方位条桥接直接遍历。 */
    public static List<TacticalMarker> allMarkers() {
        if (EXTERNAL.isEmpty()) return List.copyOf(MARKERS.values());
        List<TacticalMarker> out = new ArrayList<>(MARKERS.size() + EXTERNAL.size());
        out.addAll(MARKERS.values());
        out.addAll(EXTERNAL.values());
        return out;
    }

    /**
     * 联动系统放置/更新一枚带稳定 ID 的外部标点（v2.5.7）：同 ID 再次调用为原地更新
     * （位置/类型/时长刷新，不新增条目，同样触发 ADDED 事件）；生命周期到点自动过期
     * （EXPIRED 事件）。外部标点独立于玩家手动 5 名额（上限 16，超量按插入序逐出最旧），
     * 并进入全部四个渲染视图（大地图/小地图/世界 HUD/方位条）。
     *
     * <p>世界作用域：未进世界时拒绝落点（与玩家标点同口径）；换世界时全部外部标点
     * 清除（CLEARED 事件逐枚触发），联动系统应在世界就绪后重新放置。</p>
     *
     * @param id 联动系统内稳定标识（如 {@code "gwo:extract_north"}）；null/空白拒绝
     * @param ttlMs 存活时长毫秒（&le;0 按 60 秒默认；上限 24 小时，与对局计时同口径）
     */
    public static void placeExternalMarker(String id, TacticalMarker.Type type, Vec3 position, long ttlMs) {
        if (id == null || id.isBlank() || type == null || position == null) return;
        Minecraft mc = Minecraft.getInstance();
        maintain(mc, Util.getMillis());
        if (mc.level == null || mc.player == null) return;
        long ttl = ttlMs <= 0 ? TacticalMarkerLogic.LIFETIME_MS : Math.min(ttlMs, MAX_EXTERNAL_TTL_MS);
        TacticalMarker marker = new TacticalMarker(type, position, null, Util.getMillis(), ttl);
        // 容量控制先于写入：同 ID 更新豁免逐出（predicate 排除自身），更新不触发自逐出。
        for (var evicted : TacticalMarkerLogic.evictOldestExternal(
                EXTERNAL, key -> !key.equals(id), MAX_EXTERNAL_MARKERS)) {
            fire(TacticalMarkerListener.Cause.EXPIRED, evicted.getValue());
        }
        EXTERNAL.put(id, marker);
        fire(TacticalMarkerListener.Cause.ADDED, marker);
    }

    /** 移除一枚外部标点（联动系统主动撤销；不存在返回 false，不触发事件）。 */
    public static boolean removeExternalMarker(String id) {
        if (id == null) return false;
        TacticalMarker removed = EXTERNAL.remove(id);
        if (removed != null) fire(TacticalMarkerListener.Cause.REMOVED, removed);
        return removed != null;
    }

    /** 清除全部外部标点（玩家手动标点不动）；返回清除数量，逐枚触发 CLEARED 事件。 */
    public static int clearExternalMarkers() {
        int count = EXTERNAL.size();
        for (TacticalMarker marker : List.copyOf(EXTERNAL.values())) {
            fire(TacticalMarkerListener.Cause.CLEARED, marker);
        }
        EXTERNAL.clear();
        return count;
    }


    /**
     * Read-only snapshot used by the tactical map. The same marker objects continue to drive the
     * world-space HUD and the compass bridge, so map/HUD/compass can never drift into separate state.
     * v2.5.7：合并玩家手动与外部联动两池（玩家侧在前）。
     */
    public static List<TacticalMarker> snapshot(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        maintain(mc, Util.getMillis());
        if (mc.level == null || hasNoMarkers()) return List.of();
        return allMarkers();
    }

    /**
     * Adds a location ping from the full-screen map into the exact same store used by world pings.
     * Closing the map therefore makes the ping immediately visible in TacticalMarkerHud and,
     * when enabled, CompassMarkerBridge.
     */
    public static void placeMapLocation(Vec3 position) {
        placeMapMarker(TacticalMarker.Type.LOCATION, position);
    }

    /** Places a marker of any type from the full-screen map into the shared store (keyed per type+block). */
    public static void placeMapMarker(TacticalMarker.Type type, Vec3 position) {
        Minecraft mc = Minecraft.getInstance();
        maintain(mc, Util.getMillis());
        if (mc.level == null || mc.player == null || position == null) return;
        BlockPos block = BlockPos.containing(position);
        TacticalMarker marker = new TacticalMarker(type, position, null, Util.getMillis());
        TacticalMarkerLogic.putMarker(MARKERS, new MapMarker(type, block), marker);
        fire(TacticalMarkerListener.Cause.ADDED, marker);
    }

    /** Clears every marker (location, enemy and item) from the local store. */
    public static void clearAllMarkers() {
        for (TacticalMarker marker : List.copyOf(MARKERS.values())) {
            fire(TacticalMarkerListener.Cause.CLEARED, marker);
        }
        MARKERS.clear();
    }

    /** Clears user location pings without touching enemy/item marks. */
    public static void clearLocationMarkers() {
        List<TacticalMarker> removed = null;
        for (TacticalMarker marker : MARKERS.values()) {
            if (marker.type() == TacticalMarker.Type.LOCATION) {
                if (removed == null) removed = new ArrayList<>();
                removed.add(marker);
            }
        }
        if (removed == null) return;
        MARKERS.values().removeAll(removed);
        for (TacticalMarker marker : removed) fire(TacticalMarkerListener.Cause.CLEARED, marker);
    }

    private static void mark(TargetHit hit, boolean doubleClick) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        if (doubleClick) {
            if (hit == null) hit = directionEndpoint(mc);
            if (hit != null) {
                Object key = hit.entity != null ? hit.entity.getUUID() : hit.locationKey;
                TacticalMarker enemy = new TacticalMarker(TacticalMarker.Type.ENEMY, hit.position, null, now);
                TacticalMarkerLogic.upgrade(MARKERS, firstWrite, key, enemy,
                        previous -> previous.valid(mc.level, now));
                fire(TacticalMarkerListener.Cause.ADDED, enemy);
            }
            firstWrite = null;
            return;
        }
        firstWrite = null;
        if (hit == null) return;
        if (hit.entity != null && (!hit.entity.isAlive() || hit.entity.isRemoved() || hit.entity.level() != mc.level)) return;
        if (hit.entity instanceof ItemEntity item) {
            if (!item.getItem().isEmpty()) {
                TacticalMarker marker = new TacticalMarker(TacticalMarker.Type.ITEM, hit.position, item, now);
                firstWrite = TacticalMarkerLogic.writeImmediate(MARKERS, item.getUUID(), marker);
                fire(TacticalMarkerListener.Cause.ADDED, marker);
            }
        } else {
            TacticalMarker marker = new TacticalMarker(TacticalMarker.Type.LOCATION, hit.position, null, now);
            firstWrite = TacticalMarkerLogic.writeImmediate(MARKERS, hit.locationKey, marker);
            fire(TacticalMarkerListener.Cause.ADDED, marker);
        }
    }

    private static TargetHit directionEndpoint(Minecraft mc) {
        var camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return null;
        Vec3[] ray = TacticalMarkerHud.centerRay(camera);
        Vec3 position = ray[0].add(ray[1].scale(MAX_DISTANCE));
        return new TargetHit(position, null, new DirectionalPoint(BlockPos.containing(position)));
    }

    static TargetHit raycast(Minecraft mc) {
        var camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return null;
        Vec3[] ray = TacticalMarkerHud.centerRay(camera);
        Vec3 start = ray[0], end = start.add(ray[1].scale(MAX_DISTANCE));
        var block = mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        boolean blockHit = block.getType() == HitResult.Type.BLOCK;
        if (blockHit) end = block.getLocation();
        double nearest = start.distanceToSqr(end);
        Entity selected = null;
        Vec3 selectedPoint = null;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        // Vanilla excludes ItemEntity from isPickable(), so drops must be included explicitly.
        for (Entity entity : mc.level.getEntities(mc.player, new AABB(start, end).inflate(1),
                e -> e.isAlive() && !e.isSpectator() && (e instanceof ItemEntity || e.isPickable()))) {
            if (entity instanceof ItemEntity item && item.getItem().isEmpty()) continue;
            AABB bounds = entity.getBoundingBox().move(entity.getPosition(partial).subtract(entity.position()))
                    .inflate(entity instanceof ItemEntity ? 0.12 : entity.getPickRadius());
            Vec3 point = bounds.contains(start) ? start : bounds.clip(start, end).orElse(null);
            if (point == null) continue;
            double distance = point.distanceToSqr(start);
            if (distance <= nearest) {
                nearest = distance;
                selected = entity;
                selectedPoint = point;
            }
        }
        if (selected != null) return new TargetHit(selectedPoint, selected, selected.getUUID());
        return blockHit ? new TargetHit(block.getLocation(), null, new Surface(block.getBlockPos(), block.getDirection())) : null;
    }

    record TargetHit(Vec3 position, Entity entity, Object locationKey) {}
    private record Surface(BlockPos pos, Direction face) {}
    private record MapMarker(TacticalMarker.Type type, BlockPos pos) {}
    private record DirectionalPoint(BlockPos pos) {}
}
