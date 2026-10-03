package dev.tactical.mixin;

import dev.tactical.BagState;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @Inject(method="add(ILnet/minecraft/world/item/ItemStack;)Z",at=@At("HEAD"),cancellable=true)
    private void tactical$insert(int slot,ItemStack stack,CallbackInfoReturnable<Boolean> ci) {
        var inv=(Inventory)(Object)this;
        if(!inv.player.level().isClientSide) ci.setReturnValue(BagState.of(inv.player).insert(inv.player,stack));
    }
}
