package dev.herrastudio.raidcore.ui.decal;

import com.sgr792.gwo.client.render.fx.BulletDecalManager;
import dev.herrastudio.raidcore.RaidCore;
import dev.herrastudio.raidcore.network.ImpactDecalNetwork;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Scoped construction data only: GWO still owns decal storage, rendering and lifetime. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class GwoProjectedDecals {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private GwoProjectedDecals() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ImpactDecalNetwork.clientReceiver = GwoProjectedDecals::accept);
    }

    public static void accept(ImpactDecalNetwork.ProjectedImpact payload) {
        Context previous = ACTIVE.get();
        Context context = new Context(payload);
        ACTIVE.set(context);
        try {
            BulletDecalManager.accept(payload.impact());
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    public static void surface(AABB bounds, Vec3 hit, Direction face) {
        Context context = ACTIVE.get();
        if (context == null || face != context.face) return;
        if (face.getAxis() == Direction.Axis.X) {
            context.minU = bounds.minY - hit.y + 1.0e-5;
            context.maxU = bounds.maxY - hit.y - 1.0e-5;
        } else {
            context.minU = bounds.minX - hit.x + 1.0e-5;
            context.maxU = bounds.maxX - hit.x - 1.0e-5;
        }
        if (face.getAxis() == Direction.Axis.Z) {
            context.minV = bounds.minY - hit.y + 1.0e-5;
            context.maxV = bounds.maxY - hit.y - 1.0e-5;
        } else {
            context.minV = bounds.minZ - hit.z + 1.0e-5;
            context.maxV = bounds.maxZ - hit.z - 1.0e-5;
        }
    }

    public static float[] vertices(double x, double y, double z, Direction face, float halfSize, float rotation) {
        Context context = ACTIVE.get();
        if (context == null || face != context.face || context.projection.stretch() < 1.0001) return null;
        var mesh = context.projection.mesh(halfSize, rotation, context.minU, context.maxU, context.minV, context.maxV);
        context.mesh = new GwoImpactMesh(x, y, z, face, mesh);
        return context.mesh.corners();
    }

    public static GwoImpactMesh currentMesh() {
        Context context = ACTIVE.get();
        return context == null ? null : context.mesh;
    }

    private static final class Context {
        final Direction face;
        final ImpactProjection projection;
        double minU = Double.NEGATIVE_INFINITY, maxU = Double.POSITIVE_INFINITY;
        double minV = Double.NEGATIVE_INFINITY, maxV = Double.POSITIVE_INFINITY;
        GwoImpactMesh mesh;

        Context(ImpactDecalNetwork.ProjectedImpact payload) {
            face = payload.impact().direction();
            int axis = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
            projection = ImpactProjection.fromVelocity(payload.velocityX(), payload.velocityY(), payload.velocityZ(), axis);
        }
    }
}
