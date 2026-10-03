package dev.tactical.mixin;

import dev.tactical.Rules;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class OffhandSwapMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
    private void tactical$offhand(ServerboundPlayerActionPacket packet,CallbackInfo ci) {
        // Let vanilla enqueue packets before reading inventory state.
        if(!player.serverLevel().getServer().isSameThread()) return;
        if(packet.getAction()==ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && !Rules.accepts(player.getInventory().selected,player.getOffhandItem())) ci.cancel();
    }
}
