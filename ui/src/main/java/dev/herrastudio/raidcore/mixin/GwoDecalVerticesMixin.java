package dev.herrastudio.raidcore.mixin;

import dev.herrastudio.raidcore.ui.decal.GwoProjectedDecals;
import dev.herrastudio.raidcore.ui.decal.ProjectedDecal;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.sgr792.gwo.client.render.fx.BulletDecalManager", remap = false)
public abstract class GwoDecalVerticesMixin {
    @Inject(method = "precomputeVertices", at = @At("HEAD"), cancellable = true)
    private static void raidcore$project(double x, double y, double z, Direction face, float halfSize,
                                         float rotation, CallbackInfoReturnable<float[]> callback) {
        float[] vertices = GwoProjectedDecals.vertices(x, y, z, face, halfSize, rotation);
        if (vertices != null) callback.setReturnValue(vertices);
    }

    @Inject(method = "emitDecal", at = @At("HEAD"), cancellable = true)
    private static void raidcore$emitMesh(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix,
                                          @Coerce Object decal, float alpha, int light, CallbackInfo callback) {
        if (decal instanceof ProjectedDecal projected && projected.raidcore$emit(consumer, pose, matrix, alpha, light)) {
            callback.cancel();
        }
    }
}
