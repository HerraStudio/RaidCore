package dev.tactical.profile;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ProfilePackets {
    public static Consumer<State> receiver=p->{};
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("tactical_inventory",name); }
    public record Request(boolean open) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(id("profiles_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.open),b->new Request(b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Edit(int request,int revision,String item,String content,int width,int height,String rarity,boolean reset) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=new Type<>(id("profiles_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Edit> CODEC=StreamCodec.of((b,p)->{
            b.writeInt(p.request); b.writeInt(p.revision); b.writeUtf(p.item,256); b.writeUtf(p.content,256);
            b.writeInt(p.width); b.writeInt(p.height); b.writeUtf(p.rarity,32); b.writeBoolean(p.reset);
        },b->new Edit(b.readInt(),b.readInt(),b.readUtf(256),b.readUtf(256),b.readInt(),b.readInt(),b.readUtf(32),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(CompoundTag data,boolean open,boolean editable,int request,boolean accepted,String notice) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(id("profiles_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{
            b.writeNbt(p.data); b.writeBoolean(p.open); b.writeBoolean(p.editable); b.writeInt(p.request);
            b.writeBoolean(p.accepted); b.writeUtf(p.notice,512);
        },b->new State(b.readNbt(),b.readBoolean(),b.readBoolean(),b.readInt(),b.readBoolean(),b.readUtf(512)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("1");
        registrar.playToServer(Request.TYPE,Request.CODEC,(p,ctx)->{
            if(ctx.player() instanceof ServerPlayer player) ProfileService.send(player,p.open,-1,true,"");
        });
        registrar.playToServer(Edit.TYPE,Edit.CODEC,(p,ctx)->{
            if(ctx.player() instanceof ServerPlayer player) ProfileService.edit(player,p);
        });
        registrar.playToClient(State.TYPE,State.CODEC,(p,ctx)->receiver.accept(p));
    }
}
