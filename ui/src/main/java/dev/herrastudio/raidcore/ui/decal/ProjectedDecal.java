package dev.herrastudio.raidcore.ui.decal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/** Implemented only by client GWO decals; mesh data expires with the original decal. */
public interface ProjectedDecal {
    GwoImpactMesh raidcore$mesh();
    boolean raidcore$emit(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix, float alpha, int light);
}
