package dev.tactical.mixin;

import dev.tactical.raid.RaidDeath;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class RaidDeathMixin {
    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void tactical$raidEquipment(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player && RaidDeath.applies(player)) {
            RaidDeath.dropEquipment(player);
            callback.cancel();
        }
    }
}
