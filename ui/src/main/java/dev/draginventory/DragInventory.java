package dev.draginventory;
import dev.draginventory.client.compass.CompassConfig;
import dev.draginventory.client.map.MapConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DragInventory {
    /**
     * v2.5.4：启动自检 banner。动机——v2.5.3 一次实机排障（游戏卡红色加载界面）中，
     * 用户日志里本模组零输出：无法判断 construct 是否执行、配置阶段是否推进，
     * 排障只能靠排除法。此 banner 提供一个确定性锚点：它出现 ⇒ 本模组 construct 完成；
     * 连同 MapConfigEvents 的 marker 日志，可完整覆盖“construct → config → 常见故障区”。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("DragInventory");

    private DragInventory() {}

    public static void register(IEventBus modBus, ModContainer modContainer) {
        LOGGER.info("RaidCore HUD initialized: version={} (source=Drag Inventory 2.6.1, dist={}, java={})",
                modContainer.getModInfo().getVersion(),
                FMLEnvironment.dist,
                System.getProperty("java.vm.version"));
        PlayerStamina.ATTACHMENTS.register(modBus);
        // HERRA 方位条：独立客户端配置（draginventory-compass-client.toml），
        // 注册入口集中在此，模块内部不再依赖主类。
        modContainer.registerConfig(CompassConfig.type(), CompassConfig.SPEC, CompassConfig.fileName());
        modContainer.registerConfig(MapConfig.type(), MapConfig.SPEC, MapConfig.fileName());
        modContainer.registerConfig(ModConfig.Type.CLIENT, FastSwitchConfig.SPEC, "draginventory-switch-client.toml");
    }
}
