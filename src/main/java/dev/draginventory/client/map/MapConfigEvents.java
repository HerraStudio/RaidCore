package dev.draginventory.client.map;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 战术地图配置的 MOD 总线事件：与 FML 配置系统的加载/重载/卸载对账。
 *
 * <p>关键背景（保存竞态）：{@code SPEC.save()} 写盘后，FML 的文件监视线程会
 * 异步重读文件并<b>整体替换</b>内存配置映射。若用户在“落盘 → 监视线程重载完成”
 * 的窗口内继续修改配置，新值会被旧文件内容覆盖，表现为设置界面
 * “开关点了没反应 / 滤镜跳过去又跳回来”。</p>
 *
 * <p>对策见 {@link MapConfig#onConfigReloaded}：写前 journal + 重载对账重放。
 * 注意 Reloading 事件可能出现在两个时机：save() 内同步触发（主线程）与
 * 监视线程异步触发——对账逻辑对两者都幂等。</p>
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MapConfigEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("DragInventory/MapConfig");

    private MapConfigEvents() {}

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (isMapConfig(event)) {
            migrateLegacyConfig();
            retuneMinimapSize();
            retuneZoomRange();
            MapMatchTimer.setDefaultDuration(MapConfig.MATCH_TIMER_DURATION.get());
            MapConfig.onConfigReloaded();
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (isMapConfig(event)) {
            MapMatchTimer.setDefaultDuration(MapConfig.MATCH_TIMER_DURATION.get());
            MapConfig.onConfigReloaded();
        }
    }

    @SubscribeEvent
    public static void onConfigUnloading(ModConfigEvent.Unloading event) {
        if (isMapConfig(event)) {
            MapConfig.onConfigUnloaded();
        }
    }

    private static boolean isMapConfig(ModConfigEvent event) {
        return MapConfig.fileName().equals(event.getConfig().getFileName());
    }

    /**
     * v2.5.1 一次性配置迁移（标记驱动，仅在 Loading 事件触发）。
     *
     * <p><b>背景</b>：v2.4.0 的 {@code general.enabled} 是"存了不用"的占位配置——
     * {@code /map off} 或设置界面会把它写入 TOML，但对 M 键 / 地图毫无效果。
     * v2.5.0 将 enabled 接线为总开关后，老玩家配置里残留的 {@code enabled = false}
     * 突然生效：M 键失灵、小地图消失，表现为"模组所有 UI 不生效"。</p>
     *
     * <p><b>v2.5.0 重发版的教训</b>：当时的迁移用"旧结构特征"（contrast 键 + 无 [minimap] 段）
     * 识别升级安装，但玩家只要运行过任一版 v2.5.0，配置文件就会被改写为新结构，
     * 启发式即漏判——残留的 enabled=false 继续静默禁用一切。</p>
     *
     * <p><b>v2.5.1 规则</b>：新增 {@code general.migrated} 标记（默认 false）。首次加载时
     * 若尚未迁移，一律把 enabled 复位为默认开启并置位 migrated、落盘。与文件结构无关，
     * 确定性的一次性迁移；此后玩家主动执行的 {@code /map off}（migrated=true）被永久尊重。</p>
     */
    private static void migrateLegacyConfig() {
        if (MapConfig.MIGRATED.get()) return; // 已迁移过：尊重玩家此后的主动开关
        if (!MapConfig.ENABLED.get()) {
            MapConfig.set(MapConfig.ENABLED, true);
            LOGGER.info("Migrated stale map config: enabled reset to true "
                    + "(legacy 'enabled = false' from the pre-wiring era would silently "
                    + "disable the M key and minimap)");
        }
        MapConfig.set(MapConfig.MIGRATED, true);
        MapConfig.flush();
        LOGGER.info("Map config one-shot migration marker written (general.migrated = true)");
    }

    /**
     * v2.5.3 一次性小地图尺寸重调（标记驱动，仅在 Loading 事件触发）。
     *
     * <p><b>背景</b>：v2.5.0–v2.5.2 的 {@code minimap.size} 默认 128，且玩家配置文件在
     * 任意一次保存时都会把默认值落盘——仅改代码里的默认值对老玩家无效（文件里已是 128）。
     * 玩家实测反馈“默认小地图过大”，因此除把默认改为 96 外，还需一次性把<b>仍为旧默认 128</b>
     * 的存量配置收敛到 96。</p>
     *
     * <p><b>规则</b>：新增 {@code minimap.size_retuned} 标记（默认 false）。首次加载时若
     * 尚未重调且 size == 128（旧默认值），则改写为 96 并落盘；此后玩家自改尺寸（含主动
     * 改回 128）被永久尊重。全新安装走新默认 96，size != 128 不触发改写，仅置位标记。</p>
     */
    private static void retuneMinimapSize() {
        if (MapConfig.MINIMAP_SIZE_RETUNED.get()) return;
        if (MapConfig.MINIMAP_SIZE.get() == 128) {
            MapConfig.set(MapConfig.MINIMAP_SIZE, 96);
            LOGGER.info("Retuned minimap size 128 -> 96 (v2.5.3 new default; "
                    + "still adjustable in map settings)");
        }
        MapConfig.set(MapConfig.MINIMAP_SIZE_RETUNED, true);
        MapConfig.flush();
        LOGGER.info("Minimap size retune marker written (minimap.size_retuned = true)");
    }

    /**
     * v2.5.5 一次性缩放范围重调（标记驱动，仅在 Loading 事件触发）。
     *
     * <p><b>背景</b>：用户反馈要求缩放范围为 108%~600%（zoom 1.62~9.0；屏幕百分比 =
     * zoom/1.5*100）。旧默认 zoom_min=0.25（17%）已低于新合法区间下限 1.08——NeoForge
     * 加载时会把它纠正为新默认 1.62，但即便不纠正也在此兕底；旧默认 zoom_max=8.0（533%）
     * 在新区间内合法，不会被自动纠正，必须显式收敛到 9.0（600%）。</p>
     *
     * <p><b>规则</b>：新增 {@code behavior.zoom_retuned} 标记（默认 false）。首次加载时若尚未
     * 重调：zoom_min 仍低于 1.08（旧默认/旧区间任意值 0.2~1.0）→ 改写为新默认 1.62；
     * zoom_max 恰为旧默认 8.0 → 改写为新默认 9.0（玩家自定的其他 2.0~16.0 值不动）。
     * 此后玩家自改缩放范围被永久尊重；全新安装直接走新默认，仅置位标记。</p>
     */
    private static void retuneZoomRange() {
        if (MapConfig.ZOOM_RETUNED.get()) return;
        double oldMin = MapConfig.ZOOM_MIN.get();
        if (oldMin < 1.08) {
            MapConfig.set(MapConfig.ZOOM_MIN, MapConfig.ZOOM_MIN.getDefault());
            LOGGER.info("Retuned zoom_min {} -> {} (v2.5.5 new default 108%)",
                    oldMin, MapConfig.ZOOM_MIN.getDefault());
        }
        if (MapConfig.ZOOM_MAX.get() == 8.0) {
            MapConfig.set(MapConfig.ZOOM_MAX, MapConfig.ZOOM_MAX.getDefault());
            LOGGER.info("Retuned zoom_max 8.0 -> {} (v2.5.5 new default 600%)",
                    MapConfig.ZOOM_MAX.getDefault());
        }
        MapConfig.set(MapConfig.ZOOM_RETUNED, true);
        MapConfig.flush();
        LOGGER.info("Zoom range retune marker written (behavior.zoom_retuned = true)");
    }
}
