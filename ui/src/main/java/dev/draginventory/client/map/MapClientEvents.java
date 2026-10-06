package dev.draginventory.client.map;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 战术地图客户端事件（Forge 总线）：
 * <ul>
 *   <li>注册游戏内指令（/herramap /map /m 三根，见 {@link MapCommands}）</li>
 *   <li>client tick 驱动配置防抖落盘（指令/界面连续修改静默 500ms 后统一写盘）</li>
 *   <li>退出世界时立即落盘兜底，避免 500ms 静默窗口内的调整丢失</li>
 *   <li>进入世界时载入手动计时的默认时长；Raid 入场快照负责启动对局倒计时，
 *       大厅和普通世界不自动开始。</li>
 *   <li>v2.5.7：client tick 驱动对局结束沿检测（{@code MapMatchTimer.tickEndDetection}，
 *       供联动系统的 setEndListener 回调）</li>
 * </ul>
 * 与 M 键轮询（{@link FactoryMapClientEvents}）、配置对账
 * （{@link MapConfigEvents}，MOD 总线）分属不同事件通道，各管各的。
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class MapClientEvents {

    private MapClientEvents() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        for (var root : MapCommands.buildRoots()) {
            event.getDispatcher().register(root);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        MapConfig.tickSave();
        // v2.5.7：对局结束沿检测（MapMatchTimer.setEndListener 的回调在此触发，约 20Hz）。
        MapMatchTimer.tickEndDetection();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        MapMatchTimer.setDefaultDuration(MapConfig.MATCH_TIMER_DURATION.get());
        MapMatchTimer.stop();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MapMatchTimer.stop();
        MapConfig.flush();
    }
}
