package dev.herrastudio.tacticalactions;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Optional GWO integration; no GWO classes enter the mod's linkage graph. */
public final class GwoPeekSupport {
    private static final String GUN_ITEM = "com.sgr792.gwo.item.ContentGunItem";
    private static final String GUN_VIEW = "com.sgr792.gwo.modular.IGunView";
    private static final ClassValue<Boolean> GUN_CLASSES = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return isGunType(type);
        }
    };
    private static long firstPersonApplications;
    private static float lastFirstPersonLean;
    private static long thirdPersonApplications;
    private static float lastThirdPersonLean;

    private GwoPeekSupport() {}

    public static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && GUN_CLASSES.get(stack.getItem().getClass());
    }

    private static boolean isGunType(Class<?> type) {
        if (type == null) return false;
        if (GUN_ITEM.equals(type.getName()) || GUN_VIEW.equals(type.getName())) return true;
        for (Class<?> contract : type.getInterfaces()) {
            if (isGunType(contract)) return true;
        }
        return isGunType(type.getSuperclass());
    }

    /** Consult GWO's current ADS state only while an actual GWO gun is held. */
    public static boolean isAiming() {
        var player = Minecraft.getInstance().player;
        if (player == null || !isGun(player.getMainHandItem())) return false;
        AimAccess access = AimAccessHolder.ACCESS;
        if (access == null) return false;
        try {
            return Boolean.TRUE.equals(access.method().invoke(access.status()));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /** Camera-space roll keeps the ADS sight center on the unchanged shooting direction. */
    public static void applyFirstPerson(PoseStack poses, float partialTick) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getCameraEntity() != mc.player
                || !mc.options.getCameraType().isFirstPerson()) return;
        float lean = TacticalActionsClient.leanAt(mc.player, partialTick);
        // Keep GD656's original camera-roll conversion, including its 55 multiplier.
        float roll = -(float) Math.toRadians(PeekPoseMath.ANGLE_DEGREES * lean) * 55.0F;
        poses.mulPose(Axis.ZP.rotationDegrees(roll));
    }

    /** Read-only diagnostics for the isolated runtime harness; no logging or persistent state. */
    public static long firstPersonApplications() { return firstPersonApplications; }
    public static float lastFirstPersonLean() { return lastFirstPersonLean; }
    public static long thirdPersonApplications() { return thirdPersonApplications; }
    public static float lastThirdPersonLean() { return lastThirdPersonLean; }

    /** Called by the native render wrapper, rather than the reusable matrix helper. */
    public static void recordFirstPersonRender(float partialTick) {
        ++firstPersonApplications;
        var player = Minecraft.getInstance().player;
        lastFirstPersonLean = player == null ? 0.0F
                : TacticalActionsClient.leanAt(player, partialTick);
    }

    /** The optional native arm hook is the sole caller, keeping helper tests separate. */
    public static void recordThirdPersonRender(LivingEntity entity) {
        if (!(entity instanceof Player player) || !isGun(player.getMainHandItem())) return;
        ++thirdPersonApplications;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        lastThirdPersonLean = TacticalActionsClient.leanAt(player, partialTick);
    }

    /** GWO sets its authored arm pose after PAL; add the upper-body lean at that boundary. */
    public static void applyAfterSetup(LivingEntity entity, PlayerModel<?> model) {
        if (model == null || !(entity instanceof Player player) || !isGun(player.getMainHandItem())) return;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float lean = TacticalActionsClient.leanAt(player, partialTick);
        float offset = PeekStates.offsetAt(player, partialTick);
        applyArm(model.leftArm, PeekPoseMath.delta("left_arm", lean, offset));
        applyArm(model.rightArm, PeekPoseMath.delta("right_arm", lean, offset));
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
    }

    private static void applyArm(ModelPart arm, PeekPoseMath.Delta delta) {
        // PAL uses upward-positive bone Y; the vanilla model uses downward-positive Y.
        arm.x += delta.dx();
        arm.y -= delta.dy();
        arm.z += delta.dz();
        arm.xRot += delta.rx();
        arm.yRot += delta.ry();
        arm.zRot += delta.rz();
    }

    private record AimAccess(Object status, Method method) {}

    private static final class AimAccessHolder {
        private static final AimAccess ACCESS = resolve();

        private static AimAccess resolve() {
            try {
                Class<?> type = Class.forName("com.sgr792.gwo.client.camera.GunWeaponStatus");
                return new AimAccess(type.getField("INSTANCE").get(null), type.getMethod("isAiming"));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }
    }
}
