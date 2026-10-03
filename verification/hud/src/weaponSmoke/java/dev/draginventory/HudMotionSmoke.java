package dev.draginventory;

import dev.draginventory.client.GwoHudBridge;
import dev.draginventory.client.WeaponHudDynamics;
import dev.draginventory.client.WeaponHudMotion;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.slf4j.LoggerFactory;

/** Real player/camera movement and real GWO firing; no production test hooks. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class HudMotionSmoke {
    private static boolean running;
    private static int tick, frames, balanced;
    private static long startedAt, lastCapture;
    private static float yaw;
    private static boolean cameraMoved, sprintMoved, airborne, landed, jumpMoved, fired, recoilMoved;
    private static org.joml.Matrix4f savedPose;
    private static Object status, motion;
    private static java.lang.reflect.Method shotAge;
    private static java.lang.reflect.Field recoil;
    private static final StringBuilder csv = new StringBuilder("frame,elapsed_ms,phase,x,y,roll,recoil,sprinting,on_ground\n");

    public static boolean running() { return running; }
    public static void start() { running = true; }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!running) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getOverlay() != null) return;
        tick++;
        if (tick == 1) {
            var statusClass = Class.forName("com.sgr792.gwo.client.camera.GunWeaponStatus");
            if (java.util.Arrays.stream(statusClass.getDeclaredMethods())
                    .noneMatch(method -> method.getName().contains("draginventory$shotImpulse")))
                throw new AssertionError("Actual GWO firing hook absent");
            status = statusClass.getField("INSTANCE").get(null);
            shotAge = statusClass.getMethod("secondsFromLastShoot");
            var state = WeaponHudDynamics.class.getDeclaredField("MOTION");
            state.setAccessible(true);
            motion = state.get(null);
            recoil = WeaponHudMotion.class.getDeclaredField("recoil");
            recoil.setAccessible(true);
            Files.createDirectories(Path.of("hud-motion"));
            mc.options.bobView().set(false); // Isolate HUD response from vanilla camera bob.
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49, 0, 1, 0);
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 49, 0, 0, 0);
            yaw = mc.player.getYRot();
            mc.player.setXRot(15);
        }
        if (tick >= 20 && tick < 60) {
            float angle = (tick - 20) / 40f * (float) Math.PI * 2;
            mc.player.setYRot(yaw + (float) Math.sin(angle) * 12);
            mc.player.setXRot(15 + (float) Math.sin(angle * 2) * 6);
        }
        if (tick == 60) { mc.player.setYRot(yaw); mc.player.setXRot(15); }
        if (tick >= 80 && tick < 120) {
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
            mc.player.setSprinting(true);
        }
        if (tick == 120) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
        }
        if (tick == 140) mc.options.keyJump.setDown(true);
        if (tick == 141) mc.options.keyJump.setDown(false);
        if (tick > 140 && tick < 185) {
            airborne |= !mc.player.onGround();
            landed |= airborne && mc.player.onGround();
        }
        if (tick == 200) mc.options.keyAttack.setDown(true);
        if (tick == 225) mc.options.keyAttack.setDown(false);
        if (tick == 255) {
            var pose = WeaponHudDynamics.currentPose();
            if (Math.abs(pose.x()) > 0.04 || Math.abs(pose.y()) > 0.04 || Math.abs(pose.roll()) > 0.01)
                throw new AssertionError("HUD motion failed to settle after firing: " + pose);
        }
        if (tick == 260) mc.setScreen(new InventoryScreen(mc.player));
        if (tick == 265) {
            if (!WeaponHudDynamics.currentPose().equals(WeaponHudMotion.REST))
                throw new AssertionError("Inventory should reset HUD motion");
            mc.setScreen(null);
        }
        if (tick == 280) {
            LoggerFactory.getLogger("HudMotionSmoke").info("HUD settled after menu: {} cameraPitch={} playerPitch={}",
                    WeaponHudDynamics.currentPose(), mc.gameRenderer.getMainCamera().getXRot(), mc.player.getXRot());
        }
        if (tick == 310) {
            if (!cameraMoved || !sprintMoved || !airborne || !landed || !jumpMoved || !fired || !recoilMoved)
                throw new AssertionError("Motion coverage camera=" + cameraMoved + " sprint=" + sprintMoved
                        + " airborne=" + airborne + " landed=" + landed + " jump=" + jumpMoved
                        + " fired=" + fired + " recoil=" + recoilMoved);
            if (!WeaponHudDynamics.currentPose().equals(WeaponHudMotion.REST))
                throw new AssertionError("Motion did not return to exact rest: " + WeaponHudDynamics.currentPose());
            if (frames < 100 || balanced < frames) throw new AssertionError("Missing frames or unbalanced HUD pose");
            Files.writeString(Path.of("hud-motion/frames.csv"), csv);
            LoggerFactory.getLogger("HudMotionSmoke").info("HUD_MOTION_SMOKE_PASS: camera, actual sprint, jump/landing, actual GWO fire; bounded pose, inventory reset, exact rest; {} frames, {} restored HUD passes", frames, balanced);
            running = false;
            mc.stop();
        }
    }

    @SubscribeEvent
    public static void beforeHud(RenderGuiLayerEvent.Pre event) {
        if (running && GwoHudBridge.isGunHud(event.getName()))
            savedPose = new org.joml.Matrix4f(event.getGuiGraphics().pose().last().pose());
    }

    @SubscribeEvent
    public static void afterHud(RenderGuiLayerEvent.Post event) {
        if (!running || !GwoHudBridge.isGunHud(event.getName())) return;
        if (savedPose == null || !savedPose.equals(event.getGuiGraphics().pose().last().pose(), 0.00001f))
            throw new AssertionError("HUD sway transform leaked into other layers");
        balanced++;
    }

    @SubscribeEvent
    public static void record(RenderGuiEvent.Post event) throws Exception {
        if (!running || tick < 2 || tick >= 260) return;
        var mc = Minecraft.getInstance();
        var pose = WeaponHudDynamics.currentPose();
        if (Math.abs(pose.x()) > WeaponHudMotion.MAX_X || Math.abs(pose.y()) > WeaponHudMotion.MAX_Y
                || Math.abs(pose.roll()) > WeaponHudMotion.MAX_ROLL)
            throw new AssertionError("HUD motion exceeded amplitude limit");
        double kick = recoil.getDouble(motion);
        if (tick >= 20 && tick < 60) cameraMoved |= Math.abs(pose.x()) > 0.8 && Math.abs(pose.y()) > 0.3;
        if (tick >= 85 && tick < 120) sprintMoved |= mc.player.isSprinting() && Math.abs(pose.y()) > 1.5;
        if (tick >= 140 && tick < 180) jumpMoved |= Math.abs(pose.y()) > 2;
        if (tick >= 200 && tick < 225) {
            fired |= (float) shotAge.invoke(status) < 0.1f;
            recoilMoved |= kick > 1 && pose.y() < -2;
        }
        long now = System.currentTimeMillis();
        if (startedAt == 0) startedAt = now;
        if (now - lastCapture < 50) return;
        lastCapture = now;
        event.getGuiGraphics().flush();
        try (var frame = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                var crop = new com.mojang.blaze3d.platform.NativeImage(480, 220, false)) {
            for (int y = 0; y < 220; y++) for (int x = 0; x < 480; x++)
                crop.setPixelRGBA(x, y, frame.getPixelRGBA(frame.getWidth() - 480 + x, frame.getHeight() - 220 + y));
            crop.writeToFile(Path.of("hud-motion/frame-%04d.png".formatted(frames)));
        }
        csv.append(frames++).append(',').append(now - startedAt).append(',').append(phase()).append(',')
                .append(pose.x()).append(',').append(pose.y()).append(',').append(pose.roll()).append(',')
                .append(kick).append(',').append(mc.player.isSprinting()).append(',').append(mc.player.onGround()).append('\n');
    }

    private static String phase() {
        if (tick < 20) return "rest";
        if (tick < 60) return "camera";
        if (tick < 80) return "settle";
        if (tick < 120) return "sprint";
        if (tick < 140) return "settle";
        if (tick < 185) return "jump";
        if (tick < 200) return "settle";
        if (tick < 225) return "fire";
        return "settle";
    }
}
