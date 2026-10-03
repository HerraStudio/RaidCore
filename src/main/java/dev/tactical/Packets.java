package dev.tactical;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class Packets {
    public static java.util.function.Consumer<State> stateReceiver=state->{};
    public record Action(int menu,int revision,int op,int source,int area,int x,int y,boolean rotated,boolean half) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("tactical_inventory","action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,p)-> {
            b.writeInt(p.menu); b.writeInt(p.revision); b.writeInt(p.op); b.writeInt(p.source);
            b.writeInt(p.area); b.writeInt(p.x); b.writeInt(p.y); b.writeBoolean(p.rotated); b.writeBoolean(p.half);
        },b->new Action(b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readBoolean(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(int menu,int revision,CompoundTag data) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("tactical_inventory","state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)-> {
            b.writeInt(p.menu); b.writeInt(p.revision); b.writeNbt(p.data);
        },b->new State(b.readInt(),b.readInt(),b.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("2");
        registrar.playToServer(Action.TYPE,Action.CODEC,(p,ctx)-> {
            if(!(ctx.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
            if(dev.tactical.crack.CrackService.locked(player)) return;
            if(p.op()==0) {
                if(player.containerMenu!=player.inventoryMenu) return;
                BagState.of(player).normalize(player);
                player.openMenu(new SimpleMenuProvider((id,inv,owner)->new BagMenu(id,inv),Component.literal("战术背包")));
            } else if(player.containerMenu instanceof BagMenu menu) menu.action(p);
        });
        registrar.playToClient(State.TYPE,State.CODEC,(p,ctx)->stateReceiver.accept(p));
    }
}
