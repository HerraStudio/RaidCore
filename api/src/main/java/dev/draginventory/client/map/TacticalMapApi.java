package dev.draginventory.client.map;

import dev.draginventory.client.TacticalMarker;
import dev.draginventory.client.TacticalMarkerListener;
import dev.draginventory.client.TacticalMarkerManager;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 战术地图联动 API（v2.5.7）——对局系统 / 联动模组的<b>唯一推荐入口</b>。
 *
 * <p>四组能力（详见 wiki《战术地图API》）：</p>
 * <ul>
 *   <li><b>标点联动</b>——外部标点（稳定 ID + 自定义存活时长 + 独立 16 名额，四视图
 *       自动同步：大地图/小地图/世界 HUD/方位条）、玩家口径放置、快照读取、
 *       增删过期清除事件监听；</li>
 *   <li><b>对局结束倒计时</b>——Provider 接管真实剩余时间（最高优先级）、手动
 *       开始/停止/加减时、结束回调、剩余读取；</li>
 *   <li><b>POI 图标</b>——{@link MapPoiProvider} 注册/注销（撤离点/首领/事件等预设图标）；</li>
 *   <li><b>地图控制</b>——编程式打开大地图/设置界面、开合状态查询。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：标点与地图控制 API 请在客户端线程调用（网络包处理器若已注册到
 * 主线程则天然满足；否则用 {@code Minecraft.getInstance().execute(...)} 转入）；监听器
 * 注册/注销与计时 API 任意线程安全（计时为 volatile 字段，监听器为 CopyOnWrite 注册表），
 * 事件与结束回调在客户端线程触发。</p>
 *
 * <p><b>稳定性</b>：本类与 {@link MapMatchTimer.Provider}、{@link MapPoiProvider}、
 * {@link TacticalMarker}（含 Type）、{@link TacticalMarkerListener} 构成 2.x 系列的
 * 稳定公开面；其余类（FactoryMap* / PlayerMarkerFX / MapConfig 内部实现等）不承诺
 * 跨版本兼容。直连 {@code TacticalMarkerManager} 的等名静态方法与门面语义一致
 * （门面为薄委托），高级用法可直接使用。</p>
 */
public final class TacticalMapApi {
    private TacticalMapApi() {}

    // ==================== 标点联动 ====================

    /**
     * 放置一枚<b>玩家口径</b>标点（与地图右键/中键同一存储：共享 5 个 FIFO 名额、
     * 60 秒生命周期、同 type+方块去重）。联动系统通常应改用
     * {@link #placeExternalMarker}（独立名额 + 稳定 ID + 自定义时长）。
     */
    public static void placeMarker(TacticalMarker.Type type, Vec3 position) {
        TacticalMarkerManager.placeMapMarker(type, position);
    }

    /**
     * 放置/更新一枚带稳定 ID 的<b>外部标点</b>：同 ID 再次调用为原地更新；独立 16 名额
     * （不占玩家手动名额）；自动进入大地图/小地图/世界 HUD/方位条全部视图。
     * 世界未加载时拒绝；换世界时全部外部标点清除（联动系统应重放）。
     *
     * @param id    稳定标识（如 {@code "gwo:extract_north"}）
     * @param ttlMs 存活毫秒（&le;0 按 60 秒；上限 24 小时）
     */
    public static void placeExternalMarker(String id, TacticalMarker.Type type, Vec3 position, long ttlMs) {
        TacticalMarkerManager.placeExternalMarker(id, type, position, ttlMs);
    }

    /** 移除一枚外部标点（不存在返回 false）。 */
    public static boolean removeExternalMarker(String id) {
        return TacticalMarkerManager.removeExternalMarker(id);
    }

    /** 清除全部外部标点（玩家手动标点不动）；返回清除数量。 */
    public static int clearExternalMarkers() {
        return TacticalMarkerManager.clearExternalMarkers();
    }

    /** 当前全部标点只读快照（玩家手动在前 + 外部在后；客户端线程调用）。 */
    public static List<TacticalMarker> markers() {
        return TacticalMarkerManager.snapshot(1.0f);
    }

    /** 清除全部标点（玩家手动 + 与 C 键同口径；外部标点用 {@link #clearExternalMarkers}）。 */
    public static void clearMarkers() {
        TacticalMarkerManager.clearAllMarkers();
    }

    /** 仅清除玩家地点标点（敌情/物资不动）。 */
    public static void clearLocationMarkers() {
        TacticalMarkerManager.clearLocationMarkers();
    }

    /** 注册标点事件监听器（ADDED/REMOVED/EXPIRED/CLEARED；任意线程注册，客户端线程回调）。 */
    public static void addMarkerListener(TacticalMarkerListener listener) {
        TacticalMarkerManager.addMarkerListener(listener);
    }

    /** 注销标点事件监听器（幂等）。 */
    public static void removeMarkerListener(TacticalMarkerListener listener) {
        TacticalMarkerManager.removeMarkerListener(listener);
    }

    // ==================== 对局结束倒计时 ====================

    /**
     * 注册对局系统 Provider（最高优先级）：注册后小地图/大地图倒计时恒取其剩余秒数，
     * 渲染层零改动；传 null 注销回到手动模式。见 {@link MapMatchTimer.Provider}。
     */
    public static void registerTimerProvider(MapMatchTimer.Provider provider) {
        MapMatchTimer.register(provider);
    }

    /** 手动开始倒计时（秒，夹进 [1, 24h]；与 /map timer 同口径）。 */
    public static void startCountdown(long seconds) {
        MapMatchTimer.start(seconds);
    }

    /** 加时/减时（秒，可负；仅手动模式有效，Provider 模式下请在对局系统侧调整）。 */
    public static void addCountdownSeconds(long seconds) {
        MapMatchTimer.addSeconds(seconds);
    }

    /** 停止倒计时（小地图计时行整行隐藏；与 /map timer off 同口径）。 */
    public static void stopCountdown() {
        MapMatchTimer.stop();
    }

    /** 剩余秒数（Provider &gt; 手动；-1 = 未运行且无 Provider，展示层据此隐藏）。 */
    public static long remainingSeconds() {
        return MapMatchTimer.remainingSeconds();
    }

    /** 剩余时间 "m:ss" 文本（如 "24:37"；未运行为 "0:00"，展示层应先判 -1）。 */
    public static String formattedRemaining() {
        return MapMatchTimer.format();
    }

    /**
     * 设置"对局结束"回调：剩余从 &gt; 0 首次到达 0 时触发一次（手动与 Provider 模式
     * 均覆盖；重新开始后可再次触发；停止不触发）；由客户端 tick 驱动（延迟 &le;1 拍）。
     */
    public static void setCountdownEndListener(Runnable listener) {
        MapMatchTimer.setEndListener(listener);
    }

    // ==================== POI 图标 ====================

    /** 注册 POI 提供者（撤离点/首领/事件等预设图标，见 {@link MapPoiProvider}）。 */
    public static void registerPoiProvider(MapPoiProvider provider) {
        MapPoiProvider.register(provider);
    }

    /** 注销 POI 提供者（对局系统关闭/重载时；幂等）。 */
    public static void unregisterPoiProvider(MapPoiProvider provider) {
        MapPoiProvider.unregister(provider);
    }

    // ==================== 地图控制 ====================

    /** 打开大地图（与 M 键 / {@code /map} 同入口；线程安全——内部转主线程执行）。 */
    public static void openMap() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(FactoryMapScreen.create()));
    }

    /** 打开地图设置界面（与 {@code /map gui} 同入口；线程安全）。 */
    public static void openMapSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(MapSettingsScreen.create()));
    }

    /** 大地图是否打开（客户端线程调用）。 */
    public static boolean isMapOpen() {
        return Minecraft.getInstance().screen instanceof FactoryMapScreen;
    }
}
