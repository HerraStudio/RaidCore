package dev.draginventory;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class StartupSmoke {
    private static boolean done;
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (done || mc.screen == null || mc.getOverlay() != null) return;
        done = true;
        boolean transformed = Arrays.stream(AbstractContainerScreen.class.getDeclaredFields())
                .anyMatch(field -> field.getName().contains("draginventory$gesture"));
        if (!transformed) throw new AssertionError("Container screen mixin was not applied");
        LoggerFactory.getLogger("DragInventorySmoke").info("DRAG_INVENTORY_SMOKE_PASS: client loaded, container mixin transformed successfully");
        mc.stop();
    }
}
