package dev.herrastudio.raidcore.mixin;

import dev.herrastudio.raidcore.ui.decal.GwoProjectedDecals;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.sgr792.gwo.client.render.fx.BulletDecalManager$Surface", remap = false)
public abstract class GwoDecalSurfaceMixin {
    @Shadow @Final private AABB bounds;

    @Inject(method = "safeHalfSize", at = @At("HEAD"))
    private void raidcore$captureSurface(Vec3 hit, Direction face, CallbackInfoReturnable<Float> callback) {
        GwoProjectedDecals.surface(bounds, hit, face);
    }
}
