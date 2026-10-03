package dev.tactical.mixin;

import dev.tactical.BagState;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public abstract class DeathMixin {
    @Inject(method="dropAll",at=@At("TAIL"))
    private void tactical$dropStorage(CallbackInfo ci) {
        var p=((Inventory)(Object)this).player;
        if(p.level().isClientSide) return;
        var state=BagState.of(p);
        for(var e:state.entries) if(!EnchantmentHelper.has(e.stack,EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) p.drop(e.stack,true,false);
        state.entries.clear();
        for(int i=0;i<3;i++) { if(!EnchantmentHelper.has(state.gear[i],EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) p.drop(state.gear[i],true,false); state.gear[i]=ItemStack.EMPTY; }
        state.save(p);
    }
}
