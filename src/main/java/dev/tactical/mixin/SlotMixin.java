package dev.tactical.mixin;

import dev.tactical.Rules;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method="mayPlace",at=@At("HEAD"),cancellable=true)
    private void tactical$filter(ItemStack stack,CallbackInfoReturnable<Boolean> ci) {
        var slot=(Slot)(Object)this;
        if(slot.container instanceof Inventory && !Rules.accepts(slot.getContainerSlot(),stack)) ci.setReturnValue(false);
    }
}
