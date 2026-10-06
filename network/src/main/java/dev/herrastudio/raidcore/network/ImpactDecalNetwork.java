package dev.herrastudio.raidcore.network;

import com.sgr792.gwo.network.ModPayloads.BlockImpactPayload;
import dev.herrastudio.raidcore.RaidCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/** One authoritative impact packet per observer, with six extra bytes for direction. */
public final class ImpactDecalNetwork {
    public static Consumer<ProjectedImpact> clientReceiver = ignored -> {};

    private ImpactDecalNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(ProjectedImpact.TYPE, ProjectedImpact.CODEC,
                (payload, context) -> clientReceiver.accept(payload));
    }

    public static void send(ServerLevel level, ChunkPos chunk, BlockImpactPayload impact,
                            Vec3 velocity, CustomPacketPayload[] extras) {
        ProjectedImpact projected = ProjectedImpact.create(impact, velocity);
        for (var player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) {
            CustomPacketPayload payload = player.connection.hasChannel(ProjectedImpact.TYPE) ? projected : impact;
            PacketDistributor.sendToPlayer(player, payload, extras);
        }
    }

    public record ProjectedImpact(BlockImpactPayload impact, short velocityX, short velocityY,
                                   short velocityZ) implements CustomPacketPayload {
        public static final Type<ProjectedImpact> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(RaidCore.MOD_ID, "projected_block_impact"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProjectedImpact> CODEC = StreamCodec.of(
                (buffer, value) -> {
                    BlockImpactPayload.STREAM_CODEC.encode(buffer, value.impact);
                    buffer.writeShort(value.velocityX);
                    buffer.writeShort(value.velocityY);
                    buffer.writeShort(value.velocityZ);
                }, buffer -> new ProjectedImpact(BlockImpactPayload.STREAM_CODEC.decode(buffer),
                        buffer.readShort(), buffer.readShort(), buffer.readShort()));

        public static ProjectedImpact create(BlockImpactPayload impact, Vec3 velocity) {
            double magnitude = Math.max(Math.abs(velocity.x), Math.max(Math.abs(velocity.y), Math.abs(velocity.z)));
            if (!Double.isFinite(magnitude) || magnitude == 0) return new ProjectedImpact(impact, (short) 0, (short) 0, (short) 0);
            double x = velocity.x / magnitude, y = velocity.y / magnitude, z = velocity.z / magnitude;
            double scale = 32767 / Math.sqrt(x * x + y * y + z * z);
            return new ProjectedImpact(impact, (short) Math.round(x * scale),
                    (short) Math.round(y * scale), (short) Math.round(z * scale));
        }

        @Override public Type<ProjectedImpact> type() { return TYPE; }
    }
}
