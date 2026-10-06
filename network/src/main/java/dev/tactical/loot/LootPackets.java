package dev.tactical.loot;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LootPackets {
    public static Consumer<State> receiver=packet->{};
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory",path); }
    public record Request(boolean open) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(id("loot_rules_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.open),b->new Request(b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Edit(int request,int revision,String block,String name,double multiplier,boolean enabled) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=new Type<>(id("loot_rules_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Edit> CODEC=StreamCodec.of((b,p)->{
            b.writeInt(p.request); b.writeInt(p.revision); b.writeUtf(p.block,256); b.writeUtf(p.name,LootRule.MAX_NAME);
            b.writeDouble(p.multiplier); b.writeBoolean(p.enabled);
        },b->new Edit(b.readInt(),b.readInt(),b.readUtf(256),b.readUtf(LootRule.MAX_NAME),b.readDouble(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Open(BlockPos position) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(id("loot_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.of((b,p)->b.writeBlockPos(p.position),b->new Open(b.readBlockPos()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(CompoundTag data,boolean open,boolean editable,int request,boolean accepted,String notice) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(id("loot_rules_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{
            b.writeNbt(p.data); b.writeBoolean(p.open); b.writeBoolean(p.editable); b.writeInt(p.request);
            b.writeBoolean(p.accepted); b.writeUtf(p.notice,512);
        },b->new State(b.readNbt(),b.readBoolean(),b.readBoolean(),b.readInt(),b.readBoolean(),b.readUtf(512)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToServer(Request.TYPE,Request.CODEC,(packet,ctx)->{
            if(ctx.player() instanceof ServerPlayer player) LootService.send(player,packet.open(),-1,true,"");
        });
        registrar.playToServer(Edit.TYPE,Edit.CODEC,(packet,ctx)->{
            if(ctx.player() instanceof ServerPlayer player) LootService.edit(player,packet);
        });
        registrar.playToServer(Open.TYPE,Open.CODEC,(packet,ctx)->{
            if(ctx.player() instanceof ServerPlayer player) LootService.open(player,packet.position());
        });
        registrar.playToClient(State.TYPE,State.CODEC,(packet,ctx)->receiver.accept(packet));
    }
    private LootPackets() {}
}
