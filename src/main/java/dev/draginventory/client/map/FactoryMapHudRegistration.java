package dev.draginventory.client.map;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * 小地图 HUD 层注册：通过 NeoForge 的 RegisterGuiLayersEvent 注册独立 GUI 层
 * （位于所有层之上），照抄 {@code CompassHudRegistration} 模式，不触碰现有 HUD 类。
 *
 * <p>显隐时机：F1 隐藏 GUI 时随 GUI 层机制一起隐藏（渲染器内部另以
 * {@code options.hideGui} 双重守卫）；大地图 / 设置界面等任何 Screen 打开时
 * 由渲染器守卫（{@code mc.screen != null}）隐藏，避免与满屏地图叠画。</p>
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FactoryMapHudRegistration {

    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath("draginventory", "factory_minimap");
    private static final FactoryMinimapHud LAYER = new FactoryMinimapHud();

    private FactoryMapHudRegistration() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // registerAboveAll：小地图位于左上角，与底部快捷栏 / 左下血条 / 顶部方位条
        // 无重叠；不被其他 HUD 元素遮挡。
        event.registerAboveAll(LAYER_ID, LAYER);
    }
}
