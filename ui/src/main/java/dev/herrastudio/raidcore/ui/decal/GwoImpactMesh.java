package dev.herrastudio.raidcore.ui.decal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/** The native GWO atlas and render type are retained; only the footprint mesh changes. */
public final class GwoImpactMesh {
    private final float[] positions;
    private final Direction face;

    public GwoImpactMesh(double x, double y, double z, Direction face, ImpactProjection.Mesh mesh) {
        this.face = face;
        positions = new float[mesh.offsets().length / 2 * 3];
        for (int i = 0; i < positions.length / 3; i++) {
            double u = mesh.offsets()[i * 2], v = mesh.offsets()[i * 2 + 1];
            positions[i * 3] = (float) (x + (face.getAxis() == Direction.Axis.X ? 0 : u));
            positions[i * 3 + 1] = (float) (y + (face.getAxis() == Direction.Axis.X ? u : face.getAxis() == Direction.Axis.Z ? v : 0));
            positions[i * 3 + 2] = (float) (z + (face.getAxis() == Direction.Axis.Z ? 0 : v));
        }
    }

    public float[] positions() { return positions; }

    public float[] corners() {
        int n = ImpactProjection.SEGMENTS, side = n + 1;
        int[] indices = {0, n, side * side - 1, n * side};
        float[] result = new float[12];
        for (int i = 0; i < 4; i++) System.arraycopy(positions, indices[i] * 3, result, i * 3, 3);
        return result;
    }

    public void emit(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix, float alpha, int light,
                     float u0, float u1, float v0, float v1) {
        int n = ImpactProjection.SEGMENTS, side = n + 1;
        for (int row = 0; row < n; row++) for (int column = 0; column < n; column++) {
            emitVertex(consumer, pose, matrix, row, column, alpha, light, u0, u1, v0, v1);
            emitVertex(consumer, pose, matrix, row, column + 1, alpha, light, u0, u1, v0, v1);
            emitVertex(consumer, pose, matrix, row + 1, column + 1, alpha, light, u0, u1, v0, v1);
            emitVertex(consumer, pose, matrix, row + 1, column, alpha, light, u0, u1, v0, v1);
        }
    }

    private void emitVertex(VertexConsumer consumer, PoseStack.Pose pose, Matrix4f matrix, int row, int column,
                            float alpha, int light, float u0, float u1, float v0, float v1) {
        int n = ImpactProjection.SEGMENTS, index = (row * (n + 1) + column) * 3;
        float u = u0 + (u1 - u0) * column / n;
        float v = v1 + (v0 - v1) * row / n;
        consumer.addVertex(matrix, positions[index], positions[index + 1], positions[index + 2])
                .setColor(1, 1, 1, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, face.getStepX(), face.getStepY(), face.getStepZ());
    }
}
