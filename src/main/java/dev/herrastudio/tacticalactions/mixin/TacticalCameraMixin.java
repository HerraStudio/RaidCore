package dev.herrastudio.tacticalactions.mixin;

import dev.herrastudio.tacticalactions.TacticalActionsClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Camera.class, priority = 900)
abstract class TacticalCameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void tacticalactions$lean(BlockGetter level, Entity entity, boolean detached,
                                      boolean reverse, float partialTick, CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        var transform = TacticalActionsClient.cameraTransform(camera, level, partialTick);
        if (transform != TacticalActionsClient.CameraTransform.NONE) {
            setPosition(camera.getPosition().add(transform.offset()));
            setRotation(camera.getYRot(), camera.getXRot(), camera.getRoll() + transform.roll());
        }
    }
}
