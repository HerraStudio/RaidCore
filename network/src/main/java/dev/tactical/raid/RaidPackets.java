package dev.tactical.raid;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Only the server can advance a raid; clients can acknowledge an existing result. */
public final class RaidPackets {
    public static Consumer<CompoundTag> snapshotReceiver = data -> {};
    public static Consumer<CompoundTag> zonesReceiver = data -> {};

    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("raid_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeNbt(packet.data()), buf -> new Snapshot(readTag(buf)));
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    public record Zones(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Zones> TYPE = new Type<>(id("raid_zones"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Zones> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeNbt(packet.data()), buf -> new Zones(readTag(buf)));
        @Override public Type<Zones> type() { return TYPE; }
    }
    public record Acknowledge(UUID session) implements CustomPacketPayload {
        public static final Type<Acknowledge> TYPE = new Type<>(id("raid_acknowledge"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Acknowledge> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUUID(packet.session()), buf -> new Acknowledge(buf.readUUID()));
        @Override public Type<Acknowledge> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> snapshotReceiver.accept(packet.data())));
        registrar.playToClient(Zones.TYPE, Zones.CODEC,
                (packet, context) -> context.enqueueWork(() -> zonesReceiver.accept(packet.data())));
        registrar.playToServer(Acknowledge.TYPE, Acknowledge.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && !RaidManager.acknowledge(player, packet.session())) {
                        var current = RaidManager.snapshot(player);
                        current.putUUID("ackRejectedSession", packet.session());
                        PacketDistributor.sendToPlayer(player, new Snapshot(current));
                    }
                }));
        RaidManager.snapshotSender = (player, data) -> PacketDistributor.sendToPlayer(player, new Snapshot(data));
        RaidManager.zonesSender = (player, data) -> PacketDistributor.sendToPlayer(player, new Zones(data));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory", path); }
    private static CompoundTag readTag(RegistryFriendlyByteBuf buf) {
        CompoundTag data = buf.readNbt();
        return data == null ? new CompoundTag() : data;
    }
    private RaidPackets() {}
}
