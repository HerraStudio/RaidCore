package dev.tactical.crack;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class CrackEvents {
    @SubscribeEvent public static void pre(ServerTickEvent.Pre event) { CrackService.tick(event.getServer(),true); }
    @SubscribeEvent public static void post(ServerTickEvent.Post event) { CrackService.tick(event.getServer(),false); }
    @SubscribeEvent public static void damage(LivingDamageEvent.Post event) {
        if(event.getEntity() instanceof ServerPlayer player) CrackService.damage(player,event.getNewDamage());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) CrackService.disconnect(player);
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { CrackService.stop(event.getServer()); }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void item(PlayerInteractEvent.RightClickItem event) {
        if(event.getEntity() instanceof ServerPlayer p && CrackService.locked(p)) event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void block(PlayerInteractEvent.RightClickBlock event) {
        if(event.getEntity() instanceof ServerPlayer p && CrackService.locked(p)) event.setCanceled(true);
    }
}
