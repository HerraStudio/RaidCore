package dev.draginventory.client.compass;

import dev.draginventory.client.TacticalMarkerLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 方位条模块的内部状态中心：
 * <ul>
 *   <li>实时朝向弹簧状态（HUD 控件每帧更新，供 API 查询）</li>
 *   <li>外部标记提供者注册表</li>
 *   <li>测试标点（指令创建，重启失效，不落盘）</li>
 *   <li>基数方位监听器（朝向进入东南西北吸附区的事件回调）</li>
 * </ul>
 */
final class CompassHub {
    /** HUD 实例维护的实时朝向。 */
    static final CompassHeading LIVE = CompassHeading.create();

    /** 外部提供者（CopyOnWrite：注册/注销可能来自任意时机）。 */
    private static final CopyOnWriteArrayList<CompassMarkerProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** 指令创建的测试标点。 */
    private static final List<CompassMark> TEST_MARKS = new ArrayList<>();

    /**
     * 测试标点寿命（毫秒）：与战术标点系统的
     * {@link TacticalMarkerLogic#LIFETIME_MS} 对齐。
     * 旧版测试标点永不过期，在方位条上无限残留，被误认为“敌人标点不同步消失”
     * （v1.5.2 修复：测试标点也在 60 秒后自动消失，与真实战术标点行为一致）。
     */
    private static final long TEST_MARK_LIFETIME_MS = TacticalMarkerLogic.LIFETIME_MS;

    /** 上一次已知的客户端世界（引用变化 = 退出/重进/切维度/重生）。 */
    private static ClientLevel lastLevel;

    // ==================== 死亡标点（自动记录，靠近后消失） ====================
    /** 死亡位置；null = 当前无死亡标点。 */
    @Nullable private static Vec3 deathPos;
    /** 死亡所在维度（同维度才显示，跨维度坐标无意义）。 */
    @Nullable private static ResourceKey<Level> deathDimension;
    /** 死亡时刻（毫秒）：作为标点创建时间，保证脉冲相位稳定不随帧重建抖动。 */
    private static long deathTime;
    /** 已在本次死亡中记录过（防止死亡动画期间重复覆盖）。 */
    private static boolean deathRecorded;

    /** 玩家靠近死亡标点多近时自动清除（米）。 */
    static final double DEATH_MARK_CLEAR_RANGE = 8.0;

    /** 基数方位监听器列表。 */
    private static final CopyOnWriteArrayList<CompassApi.CardinalListener> CARDINAL_LISTENERS = new CopyOnWriteArrayList<>();

    private CompassHub() {}

    static void registerProvider(CompassMarkerProvider provider) {
        if (provider != null) PROVIDERS.addIfAbsent(provider);
    }

    static void unregisterProvider(CompassMarkerProvider provider) {
        PROVIDERS.remove(provider);
    }

    static List<CompassMarkerProvider> providers() {
        return PROVIDERS;
    }

    static void addTestMark(CompassMark mark) {
        // 同 key 的测试标点刷新而不累加。
        TEST_MARKS.removeIf(m -> m.key().equals(mark.key()));
        TEST_MARKS.add(mark);
    }

    static void clearTestMarks() {
        TEST_MARKS.clear();
    }

    static List<CompassMark> testMarks() {
        // 惰性清理过期测试标点（渲染帧调用，主线程安全）。
        if (!TEST_MARKS.isEmpty()) {
            long now = System.currentTimeMillis();
            TEST_MARKS.removeIf(m -> now - m.createdAtMillis() >= TEST_MARK_LIFETIME_MS);
        }
        return TEST_MARKS;
    }

    /**
     * 世界切换检测（HUD 控件每帧调用）：ClientLevel 引用变化即视为换了世界
     * （退出到主菜单、连接新服务器、穿门切维度、死亡重生都会重建 level），
     * 旧世界的测试标点与死亡标点坐标已无意义，立即清空；实时朝向也复位为
     * 未初始化，避免 API 在新世界首帧读到旧世界航向。
     */
    static void syncWorld(ClientLevel level) {
        if (lastLevel != level) {
            lastLevel = level;
            TEST_MARKS.clear();
            deathPos = null;
            deathDimension = null;
            deathRecorded = false;
            LIVE.reset();
        }
    }

    // ==================== 死亡标点状态机 ====================

    /**
     * 由 HUD 控件每帧调用（非预览）：轮询玩家生死状态。
     * <ul>
     *   <li>检测到生命值归零且尚未记录 -> 记录死亡位置与维度（仅一次）</li>
     *   <li>恢复存活 -> 允许下一次死亡再次记录</li>
     * </ul>
     * 轮询式而非事件式：不依赖客户端 LivingDeathEvent 的触发时机，
     * 在任何死亡路径（普通死亡/指令 kill/下界床爆炸）下都可靠。
     */
    static void tickPlayer(net.minecraft.world.entity.player.Player player) {
        if (player == null || player.level() == null) return;
        if (player.isDeadOrDying()) {
            if (!deathRecorded) {
                deathRecorded = true;
                deathPos = player.position();
                deathDimension = player.level().dimension();
                deathTime = System.currentTimeMillis();
            }
        } else {
            // 重生后复位“已记录”标志：下次死亡能记录新位置。
            deathRecorded = false;
        }
    }

    /** 当前维度是否有存活的死亡标点。 */
    static boolean hasDeathMark(ResourceKey<Level> dimension) {
        return deathPos != null && deathDimension == dimension;
    }

    /** 死亡标点视图（每次调用重建，脉冲相位由 deathTime 保持稳定）。 */
    @Nullable
    static CompassMark deathMark(CompassPalette palette) {
        if (deathPos == null || deathDimension == null) return null;
        return new CompassMark("death", CompassMark.Kind.DEATH, deathPos,
                CompassPalette.distinctFrom(palette.accent(), 0xFF5D5D),
                net.minecraft.network.chat.Component.translatable("draginventory.compass.marker.death"),
                true, deathTime, null);
    }

    /** 死亡位置（调用方先经 {@link #hasDeathMark} 确认存在）。 */
    static Vec3 deathPosition() {
        return deathPos;
    }

    /** 手动清除死亡标点（玩家已靠近 / 世界切换）。 */
    static void clearDeathMark() {
        deathPos = null;
        deathDimension = null;
    }

    /** 基数方位监听器。 */
    static void addCardinalListener(CompassApi.CardinalListener listener) {
        if (listener != null) CARDINAL_LISTENERS.addIfAbsent(listener);
    }

    static void removeCardinalListener(CompassApi.CardinalListener listener) {
        CARDINAL_LISTENERS.remove(listener);
    }

    /**
     * 由 HUD 控件在“进入基数方位区域”边沿事件时调用（每次进入都触发，
     * 边沿检测与滞回在 {@link CompassHeading} 内，这里不做去重——
     * 旧行“仅当最近方位变化才发”会漏掉“离开后重进同一方位”与“快转扫过”）。
     */
    static void fireCardinal(int cardinal) {
        for (CompassApi.CardinalListener listener : CARDINAL_LISTENERS) {
            try {
                listener.onCardinalReached(cardinal);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Nullable
    static CompassHeading liveHeading() {
        return LIVE.isInitialized() ? LIVE : null;
    }
}
