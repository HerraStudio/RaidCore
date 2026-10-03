package dev.tactical.mixin;

import dev.tactical.Rules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class MenuSwapMixin {
    @Inject(method="clicked",at=@At("HEAD"),cancellable=true)
    private void tactical$numberKey(int slotId,int button,ClickType type,Player player,CallbackInfo ci) {
        var menu=(AbstractContainerMenu)(Object)this;
        if(type==ClickType.SWAP && button>=0 && button<9 && slotId>=0 && slotId<menu.slots.size()
                && !Rules.accepts(button,menu.slots.get(slotId).getItem())) ci.cancel();
    }
}
