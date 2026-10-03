package dev.draginventory.mixin;

import dev.draginventory.client.WeaponScroll;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class WeaponKeysMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void draginventory$compactWeaponKeys(CallbackInfo ci) {
        WeaponScroll.handleQueuedKeys();
    }
}
