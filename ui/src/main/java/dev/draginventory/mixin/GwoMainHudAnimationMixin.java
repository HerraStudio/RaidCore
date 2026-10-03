package dev.draginventory.mixin;

import dev.draginventory.client.MainWeaponHudAnimation;
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

/** Optional visual hook, within the native panel's try/finally pose scope. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.hud.GunHudEventHandler")
public abstract class GwoMainHudAnimationMixin {
    @Inject(method = "renderGunHud", at = @At(value = "INVOKE", target =
            "Lcom/sgr792/gwo/client/hud/GunHudEventHandler;renderGunHudPanel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/Minecraft;Lcom/sgr792/gwo/modular/IGunView;Lnet/minecraft/world/item/ItemStack;Lcom/sgr792/gwo/client/GltfGunRenderer;Lnet/minecraft/client/KeyMapping;Lnet/minecraft/client/KeyMapping;)V"),
            remap = false, require = 0)
    private static void draginventory$animateMainHud(GuiGraphics graphics, Minecraft minecraft,
            @Coerce Object view, ItemStack gun, @Coerce Object renderer, KeyMapping cycleMode,
            KeyMapping tacticalStance, CallbackInfo ci) {
        MainWeaponHudAnimation.apply(graphics, minecraft);
    }
}
