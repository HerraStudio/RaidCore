package dev.draginventory.client.compass;

import java.util.List;

/**
 * HERRA 方位条公开 API（供其他 HERRA 模组软联动使用）。
 *
 * <p>使用约定：调用方通过反射探测本类是否存在（或依赖 soft dependency），
 * 避免与 Drag Inventory 产生编译期硬耦合。所有方法都应保证：
 * 模组未加载时不会被调用；方位条被禁用时不抛异常。</p>
 */
public final class CompassApi {

    private CompassApi() {}

    /** 平滑后的实时罗盘航向 [0, 360)，0 = 北，顺时针增大。未初始化时返回 -1。 */
    public static float getSmoothHeading() {
        CompassHeading heading = CompassHub.liveHeading();
        return heading != null ? heading.display() : -1f;
    }

    /** 平滑航向的角速度（度/秒），右转为正。 */
    public static float getHeadingVelocity() {
        CompassHeading heading = CompassHub.liveHeading();
        return heading != null ? heading.velocityDegPerSec() : 0f;
    }

    /** 注册外部标记提供者（小地图 / 标记点 / 队友方位等）。可安全重复注册。 */
    public static void registerMarkerProvider(CompassMarkerProvider provider) {
        CompassHub.registerProvider(provider);
    }

    public static void unregisterMarkerProvider(CompassMarkerProvider provider) {
        CompassHub.unregisterProvider(provider);
    }

    /** 已注册的提供者快照（调试用；返回副本，不暴露内部可变列表）。 */
    public static List<CompassMarkerProvider> markerProviders() {
        return List.copyOf(CompassHub.providers());
    }

    /** 监听朝向吸附到基数方位（0/90/180/270 = 北/东/南/西）。 */
    public static void addCardinalListener(CardinalListener listener) {
        CompassHub.addCardinalListener(listener);
    }

    public static void removeCardinalListener(CardinalListener listener) {
        CompassHub.removeCardinalListener(listener);
    }

    /**
     * 朝向进入基数方位区域（东南西北）时的回调，参数为 0/90/180/270。
     *
     * <p>事件基于玩家真实视角的"进入区域"边沿判定：快速扫过正北会触发；
     * 离开区域后再次进入同一方位也会再次触发。区域半径由方位条配置的
     * snap_range 决定（关闭 snap_assist 时不触发事件）。</p>
     */
    public interface CardinalListener {
        void onCardinalReached(int cardinalDegrees);
    }
}
