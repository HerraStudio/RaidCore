package dev.herrastudio.raidcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.herrastudio.raidcore.ui.decal.GwoImpactMesh;
import dev.herrastudio.raidcore.ui.decal.GwoProjectedDecals;
import dev.herrastudio.raidcore.ui.decal.ProjectedDecal;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.sgr792.gwo.client.render.fx.BulletDecalManager$Decal", remap = false)
public abstract class GwoDecalMeshMixin implements ProjectedDecal {
    @Shadow @Final private float u0;
    @Shadow @Final private float u1;
    @Shadow @Final private float v0;
    @Shadow @Final private float v1;
    @Unique private GwoImpactMesh raidcore$mesh;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void raidcore$keepMesh(CallbackInfo callback) {
        raidcore$mesh = GwoProjectedDecals.currentMesh();
    }

    @Override public GwoImpactMesh raidcore$mesh() { return raidcore$mesh; }

    @Override public boolean raidcore$emit(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix, float alpha, int light) {
        if (raidcore$mesh == null) return false;
        raidcore$mesh.emit(consumer, pose, matrix, alpha, light, u0, u1, v0, v1);
        return true;
    }
}
