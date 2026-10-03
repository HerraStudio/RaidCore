package dev.draginventory.mixin;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Disables GWO's holster-gated selection when GWO is present. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.animation.DeferredHotbarHandoff")
public abstract class GwoDeferredSelectionMixin {
    @Inject(method = "requestSlot(Lnet/minecraft/world/entity/player/Inventory;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void draginventory$immediate(Inventory inventory, int requestedSlot,
                                                 CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
