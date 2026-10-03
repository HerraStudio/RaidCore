package dev.tactical.crack;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class CrackPackets {
    public static Consumer<State> receiver=state->{};
    public record Start(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Start> TYPE=new Type<>(id("crack_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Start> CODEC=StreamCodec.of((b,p)->b.writeBlockPos(p.pos()),b->new Start(b.readBlockPos()));
        public Type<Start> type() { return TYPE; }
    }
    public record Input(UUID session,int revision,int sequence,boolean cancel) implements CustomPacketPayload {
        public static final Type<Input> TYPE=new Type<>(id("crack_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,p)-> {
            b.writeUUID(p.session()); b.writeInt(p.revision()); b.writeInt(p.sequence()); b.writeBoolean(p.cancel());
        },b->new Input(b.readUUID(),b.readInt(),b.readInt(),b.readBoolean()));
        public Type<Input> type() { return TYPE; }
    }
    public record State(BlockPos pos,CompoundTag desc,String notice) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(id("crack_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)-> {
            b.writeBlockPos(p.pos()); b.writeNbt(p.desc()); b.writeUtf(p.notice());
        },b->new State(b.readBlockPos(),b.readNbt(),b.readUtf()));
        public Type<State> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToServer(Start.TYPE,Start.CODEC,(p,ctx)->ctx.enqueueWork(()-> {
            if(ctx.player() instanceof ServerPlayer player) CrackService.start(player,p.pos());
        }));
        registrar.playToServer(Input.TYPE,Input.CODEC,(p,ctx)->ctx.enqueueWork(()-> {
            if(ctx.player() instanceof ServerPlayer player) CrackService.input(player,p);
        }));
        registrar.playToClient(State.TYPE,State.CODEC,(p,ctx)->ctx.enqueueWork(()->receiver.accept(p)));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory",path); }
}
