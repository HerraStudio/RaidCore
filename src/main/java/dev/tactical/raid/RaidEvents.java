package dev.tactical.raid;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class RaidEvents {
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) { RaidCommands.register(event.getDispatcher()); }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { RaidManager.tick(event.getServer()); }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) RaidManager.login(player);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) RaidManager.logout(player);
    }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) RaidManager.respawn(player);
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { RaidManager.sendSnapshot(player); RaidManager.sendZones(player); }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { RaidManager.stop(event.getServer()); }
}
