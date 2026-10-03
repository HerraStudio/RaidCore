package dev.tactical.mixin;

import dev.tactical.BagMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerOpenersCounter.class)
public abstract class LootOpenersMixin {
    @Shadow private double maxInteractionRange;
    @Inject(method="getPlayersWithContainerOpen",at=@At("RETURN"),cancellable=true)
    private void tactical$includeLootViewers(Level level,BlockPos pos,CallbackInfoReturnable<List<Player>> result) {
        if(!(level.getBlockEntity(pos) instanceof Container container)) return;
        var viewers=new ArrayList<>(result.getReturnValue());
        for(var player:level.getEntitiesOfClass(Player.class,new AABB(pos).inflate(maxInteractionRange+4)))
            if(player.containerMenu instanceof BagMenu menu && menu.loot()!=null && menu.loot().contains(container) && !viewers.contains(player)) viewers.add(player);
        result.setReturnValue(viewers);
    }
}
