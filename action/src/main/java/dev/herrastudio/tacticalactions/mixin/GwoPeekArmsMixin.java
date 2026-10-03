package dev.herrastudio.tacticalactions.mixin;

import dev.herrastudio.tacticalactions.GwoPeekSupport;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Add lean after GWO applies its authored gun arms, without changing body or legs. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.thirdperson.ThirdPersonPlayerArmPoseApplier", remap = false)
public abstract class GwoPeekArmsMixin {
    @Inject(method = "applyAfterSetup(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/model/PlayerModel;FF)V",
            at = @At("RETURN"), remap = false, require = 1)
    private static void tacticalactions$peekArms(LivingEntity entity, PlayerModel<?> model,
                                                 float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (model != null) GwoPeekSupport.recordThirdPersonRender(entity);
        GwoPeekSupport.applyAfterSetup(entity, model);
    }
}
