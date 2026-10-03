package dev.herrastudio.tacticalactions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** GD656 cover sampling with two-tick confirmation and five-tick release hysteresis. */
public final class AutomaticPeek {
    private int active, candidate, candidateTicks, releaseTicks;

    public int tick(LocalPlayer player) {
        boolean allowed = (PeekClientConfig.AUTO_CROUCH.get() && player.isCrouching())
                || (PeekClientConfig.AUTO_AIM.get() && GwoPeekSupport.isAiming())
                || (PeekClientConfig.AUTO_STAND.get() && !player.isCrouching());
        if (!allowed) { reset(); return 0; }
        double yaw = player.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 left = new Vec3(Math.cos(yaw) * 0.24, 0, Math.sin(yaw) * 0.24);
        Vec3 eye = PeekGeometry.rawEye(player);
        var face = player.level().clip(new ClipContext(eye, eye.add(forward.scale(0.72)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        int requested = 0;
        if (face.getType() != HitResult.Type.MISS
                && Vec3.atLowerCornerOf(face.getDirection().getNormal()).dot(forward.scale(-1)) >= 0.72) {
            int center = score(player, eye, forward);
            int leftScore = score(player, eye.add(left), forward);
            int rightScore = score(player, eye.subtract(left), forward);
            if (center >= 2) {
                if (leftScore < rightScore && leftScore <= 1) requested = 1;
                else if (rightScore < leftScore && rightScore <= 1) requested = -1;
            }
        }
        if (requested == 0) {
            candidate = candidateTicks = 0;
            if (++releaseTicks >= 5) active = 0;
        } else {
            releaseTicks = 0;
            if (candidate != requested) { candidate = requested; candidateTicks = 1; }
            else if (++candidateTicks >= 2) active = requested;
        }
        return active;
    }

    private int score(LocalPlayer player, Vec3 eye, Vec3 forward) {
        int blocked = 0;
        for (double height : new double[]{-0.15, -0.55, -0.95}) {
            Vec3 start = eye.add(0, height, 0);
            if (player.level().clip(new ClipContext(start, start.add(forward.scale(0.72)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) ++blocked;
        }
        return blocked;
    }

    public void reset() { active = candidate = candidateTicks = releaseTicks = 0; }
}
