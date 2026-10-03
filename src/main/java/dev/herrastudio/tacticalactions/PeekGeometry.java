package dev.herrastudio.tacticalactions;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A virtual eye/shoulder offset. Never moves the entity or its collision box. */
public final class PeekGeometry {
    public static final double DISTANCE = 0.30;
    private PeekGeometry() {}

    public static Vec3 desiredOffset(Player player, float lean) {
        double yaw = player.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(Math.cos(yaw) * DISTANCE * lean,
                0, Math.sin(yaw) * DISTANCE * lean);
    }

    public static Vec3 rawEye(Player player) {
        // getEyePosition is intercepted by the common mixin; using it here would recurse.
        return new Vec3(player.getX(), player.getEyeY(), player.getZ());
    }

    public static Vec3 offset(Player player, float lean) {
        if (!Float.isFinite(lean) || Math.abs(lean) < 0.00001F) return Vec3.ZERO;
        Vec3 desired = desiredOffset(player, Mth.clamp(lean, -1, 1));
        return desired.scale(clearance(player.level(), player, rawEye(player), desired));
    }

    public static float clearance(BlockGetter level, Entity entity, Vec3 start, Vec3 offset) {
        double length = offset.length();
        if (length < 1.0E-8) return 1;
        double fraction = 1;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Vec3 from = start.add(x * 0.07, y * 0.07, z * 0.07);
                    var hit = level.clip(new ClipContext(from, from.add(offset),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
                    if (hit.getType() != HitResult.Type.MISS) {
                        fraction = Math.min(fraction, Math.max(0,
                                (from.distanceTo(hit.getLocation()) - 0.025) / length));
                    }
                }
            }
        }
        return (float) fraction;
    }
}
