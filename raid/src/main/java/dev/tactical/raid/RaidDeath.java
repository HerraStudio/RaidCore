package dev.tactical.raid;

import dev.tactical.BagState;
import dev.tactical.Rules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Raid equipment loss is independent of the world's keepInventory setting. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class RaidDeath {
    private static final String HANDLED = "tactical_inventory:raid_death_dropped";
    public static boolean applies(ServerPlayer player) {
        var raid = RaidManager.current(player);
        return raid != null && (raid.session.active()
                || raid.returnPending && raid.session.outcome() == RaidSession.Outcome.DEAD);
    }
    public static void dropEquipment(ServerPlayer player) {
        if (player.getPersistentData().getBoolean(HANDLED)) return;
        RaidManager.finish(player, RaidSession.Outcome.DEAD, false);
        player.getPersistentData().putBoolean(HANDLED, true);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || Rules.melee(stack)) continue;
            inventory.setItem(slot, ItemStack.EMPTY);
            player.drop(stack, true, false);
        }
        var bag = BagState.of(player);
        bag.entries.removeIf(entry -> {
            if (Rules.melee(entry.stack)) {
                // Its carrying equipment drops, so use the existing take-only recovery area.
                entry.area = 2;
                return false;
            }
            player.drop(entry.stack, true, false);
            return true;
        });
        for (int index = 0; index < bag.gear.length; index++) {
            var stack = bag.gear[index];
            if (stack.isEmpty() || Rules.melee(stack)) continue;
            bag.gear[index] = ItemStack.EMPTY;
            player.drop(stack, true, false);
        }
        var carried = player.containerMenu.getCarried();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        if (!carried.isEmpty()) {
            if (Rules.melee(carried)) bag.entries.add(new BagState.Entry(bag.nextId++, 2, 0, 0, false, carried));
            else player.drop(carried, true, false);
        }
        bag.save(player);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void clone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !event.getOriginal().getPersistentData().getBoolean(HANDLED)) return;
        var original = event.getOriginal().getInventory();
        var fresh = event.getEntity();
        for (int slot = 0; slot < original.getContainerSize(); slot++) {
            var stack = original.getItem(slot);
            if (!stack.isEmpty() && Rules.melee(stack)) fresh.getInventory().setItem(slot, stack.copy());
        }
        fresh.getInventory().setChanged();
        fresh.getPersistentData().remove(HANDLED);
        event.getOriginal().getPersistentData().remove(HANDLED);
    }
    private RaidDeath() {}
}
