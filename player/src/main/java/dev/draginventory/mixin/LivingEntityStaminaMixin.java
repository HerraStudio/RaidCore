package dev.draginventory.mixin;

import dev.draginventory.PlayerStamina;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityStaminaMixin {
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void draginventory$checkSprint(boolean sprinting, CallbackInfo callback) {
        if (!sprinting || !((Object) this instanceof Player player) || !PlayerStamina.canConsume(player)) return;
        if (!player.getData(PlayerStamina.STATE).canSprint(player.isSprinting())) {
            if (player.isSprinting()) player.setSprinting(false);
            callback.cancel();
        }
    }
}
