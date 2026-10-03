package dev.draginventory.mixin;

import dev.draginventory.client.WeaponScroll;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
public abstract class WeaponScrollMixin {
    @Redirect(method = "onScroll(JDD)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;swapPaint(D)V"))
    private void draginventory$selectWeapon(Inventory inventory, double amount) {
        if (!WeaponScroll.handle(inventory, amount)) inventory.swapPaint(amount);
    }
}
