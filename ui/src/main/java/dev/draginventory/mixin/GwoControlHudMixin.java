package dev.draginventory.mixin;

import dev.draginventory.client.GwoControlHud;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the two low-resolution native key badges and appends the laser control. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.hud.GunHudEventHandler")
public abstract class GwoControlHudMixin {
    @Inject(method = "renderWeaponModeHud", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void draginventory$renderControls(GuiGraphics graphics, Minecraft minecraft, @Coerce Object view,
            ItemStack gun, int centerX, int y, KeyMapping cycleMode, KeyMapping tacticalStance, CallbackInfo ci) {
        if (GwoControlHud.render(graphics, minecraft, view, gun, centerX, y, cycleMode, tacticalStance)) ci.cancel();
    }
}
