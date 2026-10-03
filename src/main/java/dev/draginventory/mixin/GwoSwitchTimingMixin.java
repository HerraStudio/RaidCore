package dev.draginventory.mixin;

import dev.draginventory.client.GwoSwitchTimingBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep switching task and equip-pose timers aligned with the accelerated visual channel. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.animation.AnimationDurationResolver", remap = false)
public abstract class GwoSwitchTimingMixin {
    @Inject(method = "duration(Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;F)F",
            at = @At("RETURN"), cancellable = true, require = 1)
    private static void draginventory$fastSwitchTime(String state, @Coerce Object definition, float fallback,
                                                      CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(GwoSwitchTimingBridge.adjust(state, definition, callback.getReturnValue()));
    }
}
