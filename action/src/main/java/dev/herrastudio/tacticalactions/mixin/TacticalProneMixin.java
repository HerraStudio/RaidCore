package dev.herrastudio.tacticalactions.mixin;

import dev.herrastudio.tacticalactions.TacticalActionsClient;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class TacticalProneMixin {
    @Shadow private float swimAmount;
    @Shadow private float swimAmountO;

    @Inject(method = "updateSwimAmount", at = @At("HEAD"), cancellable = true)
    private void tacticalactions$ownProneRotation(CallbackInfo ci) {
        if (TacticalActionsClient.suppressVanillaSwim((LivingEntity) (Object) this)) {
            swimAmount = swimAmountO = 0.0F;
            ci.cancel();
        }
    }

    @Inject(method = "getSwimAmount", at = @At("HEAD"), cancellable = true)
    private void tacticalactions$noSecondProneRotation(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (TacticalActionsClient.suppressVanillaSwim((LivingEntity) (Object) this)) cir.setReturnValue(0.0F);
    }
}
