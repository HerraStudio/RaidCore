package dev.draginventory.mixin;

import dev.draginventory.FastSwitchConfig;
import dev.draginventory.client.FastSwitchTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Keeps GWO's switch clip playback and blends on the same clock as its switch tasks. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.animation.GunAnimationHandler", remap = false)
public abstract class GwoSwitchPlaybackMixin {
    @ModifyArgs(method = "playState(Ljava/lang/String;Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;ZLnet/minecraft/world/item/ItemStack;FLjava/lang/String;)V",
            at = @At(value = "INVOKE", target =
                    "Lcom/sgr792/gwo/client/runtime/RuntimeAnimationChannelHandler;start(Ljava/lang/String;Ljava/lang/String;FZZFFLnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/lang/String;FLjava/lang/String;)V"),
            remap = false, require = 1)
    private void draginventory$startSwitchPlayback(Args args) {
        draginventory$scaleSwitchPlayback(args, 11, 10);
    }

    @ModifyArgs(method = "playState(Ljava/lang/String;Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;ZLnet/minecraft/world/item/ItemStack;FLjava/lang/String;)V",
            at = @At(value = "INVOKE", target =
                    "Lcom/sgr792/gwo/client/runtime/RuntimeAnimationChannelHandler;append(Ljava/lang/String;Ljava/lang/String;FZZFFLnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/lang/String;Ljava/lang/String;)V"),
            remap = false, require = 1)
    private void draginventory$appendSwitchPlayback(Args args) {
        draginventory$scaleSwitchPlayback(args, 10, -1);
    }

    @ModifyArgs(method = "appendConfiguredState(Ljava/lang/String;Ljava/lang/String;Lcom/sgr792/gwo/content/GunDefinition;Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target =
                    "Lcom/sgr792/gwo/client/runtime/RuntimeAnimationChannelHandler;append(Ljava/lang/String;Ljava/lang/String;FZZFFLnet/minecraft/world/item/ItemStack;Ljava/util/List;Ljava/lang/String;Ljava/lang/String;F)V"),
            remap = false, require = 1)
    private void draginventory$appendConfiguredSwitchPlayback(Args args) {
        draginventory$scaleSwitchPlayback(args, 10, 11);
    }

    @Unique
    private static void draginventory$scaleSwitchPlayback(Args args, int ownerIndex, int blendIndex) {
        String logicalState = args.get(9);
        String ownerId = args.get(ownerIndex);
        if (!FastSwitchTiming.isOwnedSwitch(logicalState, ownerId)) {
            return;
        }
        double configured = FastSwitchConfig.multiplier();
        float multiplier = Double.isFinite(configured) && configured >= 1.0
                ? (float) configured : 1.0f;
        args.set(5, args.<Float>get(5) * multiplier);
        if (blendIndex >= 0) {
            args.set(blendIndex, args.<Float>get(blendIndex) / multiplier);
        }
    }
}
