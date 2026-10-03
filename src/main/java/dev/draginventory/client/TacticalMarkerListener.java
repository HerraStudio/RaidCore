package dev.draginventory.client;

/**
 * 战术标点联动监听器（v2.5.7）：把标点的写入/移除/过期/清除事件推送给联动系统
 * （如"玩家在地图上放了敌情标点 → 转发给队友"的团队同步场景）。
 *
 * <p><b>注册</b>：{@code TacticalMarkerManager.addMarkerListener} 或门面
 * {@code dev.draginventory.client.map.TacticalMapApi.addMarkerListener}；
 * CopyOnWrite 注册表，任意线程可注册/注销。</p>
 *
 * <p><b>回调线程与重入约束</b>：事件在客户端线程触发（渲染或 tick 路径中）。
 * 回调内<b>不要同步调用标点写入 API</b>（place/remove/clear 族）——事件可能在
 * 遍历/清理途中触发，同步写入有并发修改风险；需要写入请 {@code Minecraft.getInstance().execute(...)}
 * 转到下一拍。回调抛出的异常会被隔离（不影响标点系统与其他监听器）。</p>
 *
 * <p><b>事件语义</b>：</p>
 * <ul>
 *   <li>{@link Cause#ADDED}——新标点写入（玩家中键/地图右键/世界 HUD 双击升级/联动系统
 *       放置；同 ID 外部标点原地更新同样触发）；</li>
 *   <li>{@link Cause#REMOVED}——联动系统主动移除一枚外部标点（{@code removeExternalMarker}）；</li>
 *   <li>{@link Cause#EXPIRED}——生命周期到点或外部容量超限逐出；</li>
 *   <li>{@link Cause#CLEARED}——批量清除（C 键/弹窗按钮/换世界/联动系统清空）逐枚触发。</li>
 * </ul>
 */
public interface TacticalMarkerListener {

    /** 事件原因。 */
    enum Cause { ADDED, REMOVED, EXPIRED, CLEARED }

    /**
     * 标点事件回调。{@code marker} 永不为 null；外部标点的稳定 ID 不在事件负载中
     * （联动系统自行维护 ID → 事件的对应关系，或快照比对）。
     */
    void onMarkerEvent(Cause cause, TacticalMarker marker);
}
