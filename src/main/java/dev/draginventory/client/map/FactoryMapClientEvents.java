package dev.draginventory.client.map;

import dev.draginventory.client.TacticalMarkerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class FactoryMapClientEvents {
    private FactoryMapClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (FactoryMapKeyBindings.OPEN_MAP.consumeClick()) {
            // /map off 后 M 键停用（反馈②：总开关对打开入口有实体效果）。
            // 错误修正：不再静默吞掉按键——actionbar 提示恢复方式，避免“M 键突然失灵
            // 且无从排查”的体验（升级玩家残留 enabled=false 时尤其关键）。
            if (!MapConfig.ENABLED.get()) {
                if (mc.player != null && mc.level != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("draginventory.map.cmd.map_disabled_hint"), true);
                }
                continue;
            }
            if (mc.screen != null) continue;
            if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) continue;
            mc.setScreen(FactoryMapScreen.create());
        }
        while (FactoryMapKeyBindings.CLEAR_MARKERS.consumeClick()) {
            if (mc.screen != null) continue;
            if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) continue;
            TacticalMarkerManager.clearAllMarkers();
        }
    }
}
