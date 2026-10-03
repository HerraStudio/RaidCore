package dev.herrastudio.raidcore.integration;

import dev.draginventory.PlayerStamina;
import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.Tactical;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Shared checks against the real NeoForge loader and populated registries. */
public final class RaidCoreSmokeChecks {
    private RaidCoreSmokeChecks() {}

    public static void verify() {
        require(ModList.get().isLoaded(RaidCore.MOD_ID), "RaidCore loader entry missing");
        for (String id : new String[]{"draginventory", "tactical_inventory", "tacticalactions"}) {
            require(!ModList.get().isLoaded(id), "Standalone mod still loaded: " + id);
        }
        require(BuiltInRegistries.ITEM.getKey(Tactical.GOLD_BAR.get()).equals(id("tactical_inventory:gold_bar")), "Gold ID changed");
        require(BuiltInRegistries.BLOCK.getKey(Tactical.SAFE.get()).equals(id("tactical_inventory:safe")), "Safe ID changed");
        require(BuiltInRegistries.MENU.getKey(Tactical.MENU.get()).equals(id("tactical_inventory:inventory")), "Menu ID changed");
        require(NeoForgeRegistries.ATTACHMENT_TYPES.getKey(PlayerStamina.STATE.get()).equals(id("draginventory:stamina")), "Stamina ID changed");
        require(java.util.Arrays.stream(net.minecraft.world.entity.player.Inventory.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("tactical$insert")), "Inventory mixin missing");
        require(java.util.Arrays.stream(net.minecraft.world.entity.Entity.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("tacticalactions$peekEye")), "Peek eye mixin missing");
    }

    public static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("RaidCore smoke: " + message);
    }
}
