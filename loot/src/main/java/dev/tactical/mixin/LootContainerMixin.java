package dev.tactical.mixin;

import dev.tactical.loot.LootSettings;
import java.util.OptionalInt;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ChestBlock.class,BarrelBlock.class})
public abstract class LootContainerMixin {
    @Redirect(method="useWithoutItem",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;"))
    private OptionalInt tactical$lootMenu(Player player,MenuProvider provider,net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,Player owner,net.minecraft.world.phys.BlockHitResult hit) {
        return LootSettings.searchable(state)?OptionalInt.empty():player.openMenu(provider);
    }
}
