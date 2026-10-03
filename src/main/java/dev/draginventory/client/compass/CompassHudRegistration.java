package dev.draginventory.client.compass;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * 方位条 HUD 层注册：通过 NeoForge 的 RegisterGuiLayersEvent 注册
 * 独立 GUI 层（位于所有层之上），完全不触碰现有 HUD 的任何类。
 * F1 隐藏 GUI 时该层与原版层一起隐藏（由 Gui 渲染入口统一控制）。
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CompassHudRegistration {

    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath("draginventory", "compass");
    private static final CompassHud LAYER = new CompassHud();

    private CompassHudRegistration() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // registerAboveAll：不被其他 HUD 元素遮挡；方位条位于屏幕顶部，与
        // 底部快捷栏 / 左下血条 / 右下枪械 HUD 无重叠。
        event.registerAboveAll(LAYER_ID, LAYER);
    }
}
