package dev.herrastudio.tacticalactions.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.herrastudio.tacticalactions.GwoPeekSupport;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** GWO renders through a fresh camera-space stack rather than vanilla's hand stack. */
@Pseudo
@Mixin(targets = "com.sgr792.gwo.client.GltfGunRenderer", remap = false)
public abstract class GwoPeekRenderMixin {
    @WrapMethod(method = "renderFirstPersonCameraSpace(Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIF)V")
    private void tacticalactions$peekGun(ItemStack stack, PoseStack poses,
                                         MultiBufferSource buffers, int light, int overlay,
                                         float partialTick, Operation<Void> original) {
        poses.pushPose();
        try {
            if (GwoPeekSupport.isGun(stack)) {
                GwoPeekSupport.recordFirstPersonRender(partialTick);
                GwoPeekSupport.applyFirstPerson(poses, partialTick);
            }
            original.call(stack, poses, buffers, light, overlay, partialTick);
        } finally {
            poses.popPose();
        }
    }
}
