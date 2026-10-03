package dev.draginventory.mixin;

import dev.draginventory.client.WeaponHudDynamics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local successful-shot signal also covers automatic/burst fire and infinite creative ammo. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.camera.GunWeaponStatus")
public abstract class GwoHudShotMixin {
    @Inject(method = "markShoot", at = @At("TAIL"), remap = false, require = 0)
    private void draginventory$shotImpulse(CallbackInfo ci) {
        WeaponHudDynamics.onShot();
    }
}
