package dev.herrastudio.raidcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sgr792.gwo.network.ModPayloads.BlockImpactPayload;
import dev.herrastudio.raidcore.network.ImpactDecalNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.sgr792.gwo.entity.projectile.BulletEntity", remap = false)
public abstract class GwoBlockImpactMixin {
    @WrapOperation(method = "onHitBlock", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/network/PacketDistributor;sendToPlayersTrackingChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"))
    private void raidcore$sendDirection(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload,
                                        CustomPacketPayload[] extras, Operation<Void> original) {
        if (payload instanceof BlockImpactPayload impact) {
            ImpactDecalNetwork.send(level, chunk, impact, ((Entity) (Object) this).getDeltaMovement(), extras);
        } else {
            original.call(level, chunk, payload, extras);
        }
    }
}
