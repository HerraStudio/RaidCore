package dev.herrastudio.tacticalactions.mixin;

import dev.herrastudio.tacticalactions.PeekGeometry;
import dev.herrastudio.tacticalactions.PeekStates;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift the aiming origin while preserving the player's position and collision box. */
@Mixin(Entity.class)
public abstract class PeekEyePositionMixin {
    // Both vanilla overloads compute their own eye vector, so each receives one offset.
    @Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true)
    private void tacticalactions$peekEye(CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player player) {
            cir.setReturnValue(cir.getReturnValue().add(
                    PeekGeometry.offset(player, PeekStates.offsetAt(player, 1.0F))));
        }
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true)
    private void tacticalactions$peekInterpolatedEye(float partialTick,
                                                      CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof Player player) {
            cir.setReturnValue(cir.getReturnValue().add(
                    PeekGeometry.offset(player, PeekStates.offsetAt(player, partialTick))));
        }
    }
}
