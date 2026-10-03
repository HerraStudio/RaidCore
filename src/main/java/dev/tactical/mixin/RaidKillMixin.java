package dev.tactical.mixin;

import dev.tactical.raid.RaidManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class RaidKillMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void tactical$acceptedKill(ServerLevel level, DamageSource source, CallbackInfo callback) {
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != (Object) this)
            RaidManager.countKill(attacker);
    }
}
