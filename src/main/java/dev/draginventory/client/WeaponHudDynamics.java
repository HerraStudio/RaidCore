package dev.draginventory.client;

import com.mojang.math.Axis;
import dev.draginventory.WeaponSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Samples once per GUI frame so the candidate row and the native gun panel share one pose. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class WeaponHudDynamics {
    private static final WeaponHudMotion MOTION = new WeaponHudMotion();
    private static WeaponHudMotion.Pose pose = WeaponHudMotion.REST;
    private static Player owner;
    private static long lastFrame;
    private static int previousSlot = -1;
    private static int pendingShots;
    private static int shotSlot = -1;

    private WeaponHudDynamics() {}

    @SubscribeEvent
    public static void sample(RenderGuiEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (!eligible(mc)) {
            reset();
            return;
        }
        int slot = mc.player.getInventory().selected;
        if (owner != mc.player) {
            reset();
            owner = mc.player;
        } else if (previousSlot != slot) {
            MOTION.clearRecoil();
            if (shotSlot != slot) pendingShots = 0;
        }
        previousSlot = slot;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1f / 60 : (now - lastFrame) / 1_000_000_000f;
        lastFrame = now;
        var camera = mc.gameRenderer.getMainCamera();
        // Actual displacement avoids bobbing when sprint is held against a wall.
        float speed = (float) Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo) * 20;
        pose = MOTION.step(dt, camera.getYRot(), camera.getXRot(), mc.player.isSprinting(), speed,
                mc.player.onGround(), (float) mc.player.getDeltaMovement().y * 20, pendingShots);
        pendingShots = 0;
    }

    /** Called only by GWO's successful local shot notification, never by attack-key state. */
    public static void onShot() {
        var mc = Minecraft.getInstance();
        if (eligible(mc)) {
            int slot = mc.player.getInventory().selected;
            if (slot != shotSlot) pendingShots = 0;
            shotSlot = slot;
            pendingShots = Math.min(4, pendingShots + 1);
        }
    }

    public static WeaponHudMotion.Pose currentPose() { return pose; }

    public static void apply(GuiGraphics graphics) {
        if (pose.equals(WeaponHudMotion.REST)) return;
        graphics.pose().translate(86 + pose.x(), 40 + pose.y(), 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(pose.roll()));
        graphics.pose().translate(-86, -40, 0);
    }

    private static boolean eligible(Minecraft mc) {
        return GwoHudBridge.installed() && mc.player != null && mc.getCameraEntity() == mc.player
                && !mc.player.isSpectator() && !mc.options.hideGui && mc.screen == null
                && mc.getOverlay() == null && !mc.isPaused()
                && WeaponSlots.isWeaponSlot(mc.player.getInventory().selected)
                && !mc.player.getMainHandItem().isEmpty();
    }

    private static void reset() {
        MOTION.reset();
        pose = WeaponHudMotion.REST;
        owner = null;
        lastFrame = 0;
        previousSlot = -1;
        pendingShots = 0;
        shotSlot = -1;
    }
}
