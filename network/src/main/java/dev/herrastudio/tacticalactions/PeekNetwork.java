package dev.herrastudio.tacticalactions;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

/** Only a direction is accepted from clients; the server computes bounded pose and eye offset. */
public final class PeekNetwork {
    public static Consumer<Snapshot> clientReceiver = ignored -> {};
    private PeekNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) PeekServer.request(player, request.direction());
        });
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (snapshot, context) -> clientReceiver.accept(snapshot));
    }

    public record Request(int direction) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(id("peek_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeByte(value.direction), buffer -> new Request(buffer.readByte()));
        @Override public Type<Request> type() { return TYPE; }
    }

    public record Snapshot(UUID player, float previous, float current,
                           float previousOffset, float currentOffset) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("peek_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.player); buffer.writeFloat(value.previous); buffer.writeFloat(value.current);
                    buffer.writeFloat(value.previousOffset); buffer.writeFloat(value.currentOffset);
                }, buffer -> new Snapshot(buffer.readUUID(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
        @Override public Type<Snapshot> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TacticalActions.MOD_ID, path);
    }
}
