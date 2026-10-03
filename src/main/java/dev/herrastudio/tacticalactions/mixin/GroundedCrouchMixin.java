package dev.herrastudio.tacticalactions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.herrastudio.tacticalactions.GroundedCrouch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class GroundedCrouchMixin {
    @Shadow private boolean crouching;
    @Unique private final GroundedCrouch tacticalactions$crouch = new GroundedCrouch();

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V"))
    private void tacticalactions$groundedInput(Input input, boolean wasSlow, float slowFactor,
                                               Operation<Void> original) {
        original.call(input, wasSlow, slowFactor);
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!tacticalactions$walking(player)) {
            tacticalactions$crouch.reset();
            return;
        }
        boolean canStand = tacticalactions$canStand(player);
        var result = tacticalactions$crouch.update(tacticalactions$supported(player), canStand,
                crouching || player.getPose() == Pose.CROUCHING, input.shiftKeyDown, input.jumping);
        input.shiftKeyDown = result.crouch();
        input.jumping = result.jump();
        // Preserve vanilla's automatic low-ceiling crouch; never expand into solid blocks.
        crouching = result.crouch() || !canStand;
        if (!canStand) input.jumping = false;
        if (crouching) player.setSprinting(false);
        if (crouching && player.getPose() == Pose.STANDING) player.setPose(Pose.CROUCHING);
        if (!crouching && player.getPose() == Pose.CROUCHING) player.setPose(Pose.STANDING);
        // KeyboardInput already scaled movement using last tick's crouch state.
        // Correct that scaling before NeoForge's input event and vanilla movement run.
        boolean nowSlow = player.isMovingSlowly();
        if (wasSlow != nowSlow && slowFactor > 0.0F) {
            float scale = nowSlow ? slowFactor : 1.0F / slowFactor;
            input.forwardImpulse *= scale;
            input.leftImpulse *= scale;
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void tacticalactions$leaveGround(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (tacticalactions$walking(player) && !tacticalactions$supported(player)) {
            tacticalactions$crouch.leaveGround();
            player.input.shiftKeyDown = false;
            if (tacticalactions$canStand(player)) {
                crouching = false;
                if (player.getPose() == Pose.CROUCHING) player.setPose(Pose.STANDING);
            }
        }
        if (tacticalactions$walking(player) && crouching) player.setSprinting(false);
    }

    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void tacticalactions$crouchWinsSprint(CallbackInfoReturnable<Boolean> cir) {
        if (tacticalactions$walking((LocalPlayer)(Object)this) && crouching) cir.setReturnValue(false);
    }

    @Unique private static boolean tacticalactions$walking(LocalPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().flying
                && !player.isPassenger() && !player.isSleeping() && !player.isFallFlying()
                && !player.isSwimming() && !player.isInWater() && !player.isInLava()
                && !player.onClimbable() && player.getForcedPose() == null
                && Minecraft.getInstance().screen == null;
    }

    @Unique private static boolean tacticalactions$supported(LocalPlayer player) {
        if (!player.onGround()) return false;
        AABB box = player.getBoundingBox();
        AABB feet = new AABB(box.minX + 1.0E-5, box.minY - 0.025, box.minZ + 1.0E-5,
                box.maxX - 1.0E-5, box.minY + 1.0E-5, box.maxZ - 1.0E-5);
        return player.level().getBlockCollisions(player, feet).iterator().hasNext();
    }

    @Unique private static boolean tacticalactions$canStand(LocalPlayer player) {
        return player.level().noCollision(player,
                player.getDimensions(Pose.STANDING).makeBoundingBox(player.position()).deflate(1.0E-7));
    }
}
