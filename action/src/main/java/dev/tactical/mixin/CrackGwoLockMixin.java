package dev.tactical.mixin;

import dev.tactical.crack.CrackService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.sgr792.gwo.network.ModPayloads",remap=false)
public abstract class CrackGwoLockMixin {
    @Inject(method={"handleFire","handleReload","handlePerRoundReload","handleMelee","handleChargeState","handleArmorPlateAction"},
            at=@At("HEAD"),cancellable=true,require=1)
    private static void tactical$blockGunInput(@Coerce Object packet,IPayloadContext context,CallbackInfo callback) {
        if(context.player() instanceof ServerPlayer player && CrackService.locked(player)) callback.cancel();
    }
    @Inject(method="processAcceptedFireRequest",at=@At("HEAD"),cancellable=true)
    private static void tactical$blockDeferredFire(ServerPlayer player,@Coerce Object packet,CallbackInfo callback) {
        if(CrackService.locked(player)) callback.cancel();
    }
}
