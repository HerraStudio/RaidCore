package dev.draginventory.client;

import dev.draginventory.WeaponSlots;
import dev.draginventory.mixin.CarriedItemSync;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Immediate four-slot weapon selection. GWO animations remain display-only. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WeaponScroll {
    private WeaponScroll() {}

    public static boolean handle(Inventory inventory, double scrollAmount) {
        if (inventory == null || scrollAmount == 0 || Minecraft.getInstance().screen != null) return false;
        int current = inventory.selected;
        int target = WeaponSlots.next(current, scrollAmount, occupied(inventory));
        select(inventory, target);
        // Consume even a no-op so vanilla cannot scroll into an empty slot or slots 5-9.
        return true;
    }

    public static int occupied(Inventory inventory) {
        int mask = 0;
        for (int slot = WeaponSlots.FIRST; slot <= WeaponSlots.LAST; slot++) {
            if (!inventory.getItem(slot).isEmpty()) mask |= 1 << slot;
        }
        return mask;
    }

    public static void select(Inventory inventory, int target) {
        if (inventory == null || target < 0 || target >= Inventory.getSelectionSize()) return;
        int previous = inventory.selected;
        if (previous == target) return;
        inventory.selected = target;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null && mc.player.getInventory() == inventory) {
            ((CarriedItemSync) mc.gameMode).draginventory$syncCarriedItem();
        }
        if (WeaponSlots.isWeaponSlot(previous) || WeaponSlots.isWeaponSlot(target)) {
            WeaponSwitchAnimation.trigger(previous, target);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_REPEAT) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!canSelect(minecraft)) return;
        var input = InputConstants.getKey(event.getKey(), event.getScanCode());
        for (int binding = WeaponSlots.FIRST; binding <= WeaponSlots.LAST; binding++) {
            if (minecraft.options.keyHotbarSlots[binding].isActiveAndMatches(input)) consumeBinding(minecraft, binding);
        }
    }

    /** Runs before vanilla handles queued hotbar clicks (also covers mouse-bound hotbar keys). */
    public static void handleQueuedKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canSelect(minecraft)) return;
        for (int binding = WeaponSlots.FIRST; binding <= WeaponSlots.LAST; binding++) consumeBinding(minecraft, binding);
    }

    private static void consumeBinding(Minecraft minecraft, int binding) {
        boolean clicked = false;
        while (minecraft.options.keyHotbarSlots[binding].consumeClick()) clicked = true;
        if (!clicked) return;
        var inventory = minecraft.player.getInventory();
        int target = WeaponSlots.slotForBinding(binding, occupied(inventory));
        if (target >= 0) select(inventory, target);
    }

    private static boolean canSelect(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()) return false;
        // Keep creative hotbar preset save/load combinations owned by vanilla.
        return !minecraft.player.isCreative() || (!minecraft.options.keySaveHotbarActivator.isDown()
                && !minecraft.options.keyLoadHotbarActivator.isDown());
    }
}
