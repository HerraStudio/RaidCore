package dev.tactical.mixin;

import dev.tactical.crack.CrackService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CrackInputLockMixin {
    @Shadow public ServerPlayer player;
    @Inject(method={"handleMovePlayer","handlePlayerAction","handleSetCarriedItem","handleUseItem","handleUseItemOn","handleInteract","handleContainerClick"},
            at=@At("HEAD"),cancellable=true)
    private void tactical$blockCrackActions(CallbackInfo callback) {
        if(CrackService.locked(player)) callback.cancel();
    }
}
