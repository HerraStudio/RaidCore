package dev.herrastudio.tacticalactions;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID)
public final class PeekServer {
    private static final Map<ServerPlayer, Input> INPUTS = new WeakHashMap<>();
    private PeekServer() {}

    public static void request(ServerPlayer player, int direction) {
        if (direction < -1 || direction > 1) return;
        INPUTS.put(player, new Input(direction, player.tickCount));
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Input input = INPUTS.get(player);
        int direction = input == null || player.tickCount - input.tick > 60 || !PeekStates.canPeek(player)
                ? 0 : input.direction;
        float before = PeekStates.current(player);
        PeekStates.tick(player, direction);
        float after = PeekStates.current(player);
        if (before != after || (after != 0 && player.tickCount % 20 == 0)) broadcast(player);
    }

    private static void broadcast(ServerPlayer subject) {
        var snapshot = snapshot(subject);
        // Optional channels preserve joining servers/clients without this mod.
        if (subject.connection.hasChannel(PeekNetwork.Snapshot.TYPE)) PacketDistributor.sendToPlayer(subject, snapshot);
        for (ServerPlayer viewer : subject.serverLevel().getChunkSource().chunkMap.getPlayers(subject.chunkPosition(), false)) {
            if (viewer == subject) continue;
            if (viewer.connection.hasChannel(PeekNetwork.Snapshot.TYPE)) PacketDistributor.sendToPlayer(viewer, snapshot);
        }
    }

    private static PeekNetwork.Snapshot snapshot(ServerPlayer player) {
        return new PeekNetwork.Snapshot(player.getUUID(), PeekStates.leanAt(player, 0), PeekStates.current(player),
                PeekStates.offsetAt(player, 0), PeekStates.offsetAt(player, 1));
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer subject
                && viewer.connection.hasChannel(PeekNetwork.Snapshot.TYPE)) {
            PacketDistributor.sendToPlayer(viewer, snapshot(subject));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { INPUTS.remove(player); PeekStates.reset(player); }
    }

    @SubscribeEvent
    public static void entityLeft(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Player player) PeekStates.reset(player);
    }

    private record Input(int direction, int tick) {}
}
