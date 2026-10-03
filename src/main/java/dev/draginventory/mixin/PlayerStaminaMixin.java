package dev.draginventory.mixin;

import dev.draginventory.PlayerStamina;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerStaminaMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void draginventory$checkJump(CallbackInfo callback) {
        var player = (Player) (Object) this;
        if (PlayerStamina.canConsume(player) && !player.getData(PlayerStamina.STATE).canJump()) {
            callback.cancel();
        }
    }
}
