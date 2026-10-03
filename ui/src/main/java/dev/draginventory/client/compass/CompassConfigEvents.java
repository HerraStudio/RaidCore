package dev.draginventory.client.compass;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方位条配置的 MOD 总线事件：与 FML 配置系统的加载/重载/卸载对账。
 *
 * <p>关键背景（v1.5.2 修复的保存竞态）：{@code SPEC.save()} 写盘后，FML 的
 * 文件监视线程会异步重读文件并<b>整体替换</b>内存配置映射。若用户在
 * “落盘 → 监视线程重载完成”的窗口内继续修改配置，新值会被旧文件内容覆盖，
 * 表现为设置界面“开关点了没反应 / 配色跳过去又跳回来 / 连跳两下”。</p>
 *
 * <p>对策见 {@link CompassConfig#onConfigReloaded}：写前日志 + 重载对账重放。
 * 注意 Reloading 事件可能出现在两个时机：save() 内同步触发（主线程）与
 * 监视线程异步触发——对账逻辑对两者都幂等。</p>
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CompassConfigEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("DragInventory/CompassConfig");

    private CompassConfigEvents() {}

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (isCompassConfig(event)) {
            retuneDefaults();
            CompassConfig.onConfigReloaded();
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (isCompassConfig(event)) {
            CompassConfig.onConfigReloaded();
        }
    }

    @SubscribeEvent
    public static void onConfigUnloading(ModConfigEvent.Unloading event) {
        if (isCompassConfig(event)) {
            CompassConfig.onConfigUnloaded();
        }
    }

    private static boolean isCompassConfig(ModConfigEvent event) {
        return CompassConfig.fileName().equals(event.getConfig().getFileName());
    }

    /**
     * v2.6.1 一次性默认预设收敛（标记驱动，仅在 Loading 事件触发）。
     *
     * <p><b>背景</b>：v2.6.0 及之前方位条出厂预设为 delta 皮肤 / offset_y=6 / width=240，
     * 玩家实测反馈默认观感不佳（偏重、易遮挡）。新出厂预设（用户实测调优）：apex 皮肤
     * （悬浮式，两端 Alpha 渐隐，视觉最轻）/ 顶部对齐（offset_y=0，与快捷对齐“贴顶”同值）
     * / 水平居中（offset_x=0，与旧默认相同）/ 2/3 屏条宽（width=427，即 640 GUI 宽下
     * 屏宽占比 2/3 预设的换算值）/ 1.0 缩放（与旧默认相同）——轻皮肤 + 渐隐两端，
     * 不遮挡地图与其他 HUD。</p>
     *
     * <p><b>规则</b>（同 minimap.size_retuned / behavior.zoom_retuned 先例）：仅改代码
     * 默认值对老玩家无效（文件里已是旧默认值），故首次加载时逐项检测——仍为旧默认值
     * 的项收敛到新默认；玩家自定过的项（≠旧默认）一律保留。置位标记并落盘，此后玩家
     * 主动改回旧值的选择被永久尊重。全新安装的配置文件直接以新默认生成，三项检测均
     * 不命中，仅写标记。</p>
     */
    private static void retuneDefaults() {
        if (CompassConfig.DEFAULTS_RETUNED.get()) return; // 已收敛过：尊重玩家此后的主动修改
        if ("delta".equals(CompassConfig.STYLE.get())) {
            CompassConfig.set(CompassConfig.STYLE, "apex");
            LOGGER.info("Retuned compass style delta -> apex (v2.6.1 new default preset)");
        }
        if (CompassConfig.OFFSET_Y.get() == 6) {
            CompassConfig.set(CompassConfig.OFFSET_Y, 0);
            LOGGER.info("Retuned compass offset_y 6 -> 0 (v2.6.1 top-aligned default preset)");
        }
        if (CompassConfig.BAR_WIDTH.get() == 240) {
            CompassConfig.set(CompassConfig.BAR_WIDTH, 427);
            LOGGER.info("Retuned compass width 240 -> 427 (v2.6.1 two-thirds-screen default preset)");
        }
        CompassConfig.set(CompassConfig.DEFAULTS_RETUNED, true);
        CompassConfig.flush();
        LOGGER.info("Compass defaults retune marker written (general.defaults_retuned = true)");
    }
}
