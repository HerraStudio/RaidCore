package dev.herrastudio.tacticalactions.visualtest;

import com.mojang.logging.LogUtils;
import dev.herrastudio.tacticalactions.TacticalActions;
import dev.herrastudio.tacticalactions.TacticalActionsKeyMappings;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/**
 * Development-only, repeatable visual exercise of the real input path.
 * Run with gradlew runClient -PvisualTest. This source set is excluded from JARs.
 */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class TacticalActionsVisualTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PHASE_TICKS = 50;
    private static final double X = 0.5;
    private static final double Y = -60;
    private static final double Z = 0.5;
    private static final Phase[] PHASES = {
            new Phase("01-third-front-standing", CameraType.THIRD_PERSON_FRONT, false, false, false, 0),
            new Phase("02-third-front-left", CameraType.THIRD_PERSON_FRONT, true, false, false, 0),
            new Phase("03-third-front-released", CameraType.THIRD_PERSON_FRONT, false, false, false, 0),
            new Phase("04-third-front-right", CameraType.THIRD_PERSON_FRONT, false, true, false, 0),
            new Phase("05-third-front-both", CameraType.THIRD_PERSON_FRONT, true, true, false, 0),
            new Phase("06-third-front-neutral", CameraType.THIRD_PERSON_FRONT, false, false, false, 0),
            new Phase("07-third-front-prone", CameraType.THIRD_PERSON_FRONT, false, false, true, 0),
            new Phase("08-third-front-rise", CameraType.THIRD_PERSON_FRONT, false, false, false, 0),
            new Phase("09-first-standing", CameraType.FIRST_PERSON, false, false, false, 0),
            new Phase("10-first-left", CameraType.FIRST_PERSON, true, false, false, 0),
            new Phase("11-first-right", CameraType.FIRST_PERSON, false, true, false, 0),
            new Phase("12-first-both", CameraType.FIRST_PERSON, true, true, false, 0),
            new Phase("13-first-released", CameraType.FIRST_PERSON, false, false, false, 0),
            new Phase("14-first-prone", CameraType.FIRST_PERSON, false, false, true, 0),
            new Phase("15-first-rise", CameraType.FIRST_PERSON, false, false, false, 0),
            new Phase("16-third-back-standing", CameraType.THIRD_PERSON_BACK, false, false, false, 0),
            new Phase("17-third-back-left", CameraType.THIRD_PERSON_BACK, true, false, false, 0),
            new Phase("18-third-back-right", CameraType.THIRD_PERSON_BACK, false, true, false, 0),
            new Phase("19-third-back-prone", CameraType.THIRD_PERSON_BACK, false, false, true, 0),
            new Phase("20-third-back-rise", CameraType.THIRD_PERSON_BACK, false, false, false, 0),
            new Phase("21-third-side-standing", CameraType.FIRST_PERSON, false, false, false, 0, true),
            new Phase("22-third-side-left", CameraType.FIRST_PERSON, true, false, false, 0, true),
            new Phase("23-third-side-right", CameraType.FIRST_PERSON, false, true, false, 0, true),
            new Phase("24-third-side-prone", CameraType.FIRST_PERSON, false, false, true, 0, true),
            new Phase("25-third-side-rise", CameraType.FIRST_PERSON, false, false, false, 0, true),
            new Phase("26-third-look-down-left", CameraType.THIRD_PERSON_FRONT, true, false, false, 35),
            new Phase("27-third-look-up-right", CameraType.THIRD_PERSON_FRONT, false, true, false, -35),
            new Phase("28-third-final-release", CameraType.THIRD_PERSON_FRONT, false, false, false, 0)
    };
    private static final Queue<String> CAPTURES = new ArrayDeque<>();
    private static boolean initialized;
    private static boolean disabled;
    private static boolean requestedProne;
    private static boolean complete;
    private static volatile boolean stageReady;
    private static int warmupTicks;
    private static int sequenceTick;
    private static Path output;
    private static ArmorStand sideCamera;
    private static String currentPhase = "warmup";

    private TacticalActionsVisualTest() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTick(ClientTickEvent.Pre event) {
        if (!Boolean.getBoolean("tacticalactions.visualTest") || disabled || complete) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (!initialized) {
            if (!initialize(mc)) return;
        }
        if (!stageReady) return;
        // Keep the isolated test moving when Codex is focused instead of the game window.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null) {
            if (warmupTicks < 100) return; // Allow only the initial world-loading screen to finish.
            fail("Unexpected screen during " + currentPhase + ": " + mc.screen.getClass().getSimpleName());
            return;
        }
        freezeView(mc);
        if (++warmupTicks < 100) return;

        int phaseIndex = sequenceTick / PHASE_TICKS;
        int phaseTick = sequenceTick % PHASE_TICKS;
        if (phaseIndex >= PHASES.length) {
            releaseInput();
            complete = true;
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.options.hideGui = false;
            log("COMPLETE: " + PHASES.length + " phases; review screenshots before accepting visuals.");
            return;
        }
        Phase phase = PHASES[phaseIndex];
        currentPhase = phase.name();
        mc.options.setCameraType(phase.side() ? CameraType.THIRD_PERSON_BACK : phase.camera());
        var cameraEntity = mc.player;
        if (mc.getCameraEntity() != cameraEntity) mc.setCameraEntity(cameraEntity);
        mc.player.setXRot(phase.pitch());
        mc.player.xRotO = phase.pitch();
        if (phaseTick == 0) {
            // Exercise shared key dispatch too: Q/E must not drop the sword/open the inventory.
            releaseInput();
            if (phase.left()) {
                KeyMapping.set(TacticalActionsKeyMappings.LEAN_LEFT.getKey(), true);
                KeyMapping.click(TacticalActionsKeyMappings.LEAN_LEFT.getKey());
            }
            if (phase.right()) {
                KeyMapping.set(TacticalActionsKeyMappings.LEAN_RIGHT.getKey(), true);
                KeyMapping.click(TacticalActionsKeyMappings.LEAN_RIGHT.getKey());
            }
        }
        TacticalActionsKeyMappings.LEAN_LEFT.setDown(phase.left());
        TacticalActionsKeyMappings.LEAN_RIGHT.setDown(phase.right());
        if (phase.prone() != requestedProne) {
            KeyMapping.click(TacticalActionsKeyMappings.PRONE.getKey());
            requestedProne = phase.prone();
        }
        if (phaseTick == 0) {
            log("BEGIN " + phase.name() + " keys=" + phase.left() + "/" + phase.right()
                    + " prone=" + phase.prone() + " camera=" + phase.camera());
        }
        if ((phase.left() != phase.right()) && phaseTick == 3) {
            CAPTURES.add(phase.name() + "-transition.png");
        }
        if (phaseTick == 35) CAPTURES.add(phase.name() + ".png");
        sequenceTick++;
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (output == null || CAPTURES.isEmpty() || mc.player == null) return;
        String filename = CAPTURES.remove();
        Vec3 cameraPosition = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 eyePosition = mc.player.getEyePosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        log("CAPTURE " + filename + " pose=" + mc.player.getPose()
                + " position=" + mc.player.position() + " camera=" + mc.options.getCameraType()
                + " cameraPos=" + cameraPosition + " eyePos=" + eyePosition
                + " cameraMinusEye=" + cameraPosition.subtract(eyePosition)
                + " roll=" + mc.gameRenderer.getMainCamera().getRoll()
                + " sword=" + mc.player.getInventory().getItem(0));
        Screenshot.grab(output.toFile(), filename, mc.getMainRenderTarget(),
                message -> log("SAVED " + filename + " " + message.getString()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sideView(ViewportEvent.ComputeCameraAngles event) {
        if (initialized && !complete && currentPhase.contains("third-side")) {
            // Camera.setup uses this rotation to place the third-person orbit camera.
            // Keep the player's own body/aim unchanged and orbit 90 degrees around it.
            event.setYaw(90);
            event.setPitch(0);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void checkInput(ClientTickEvent.Post event) {
        if (!initialized || disabled || complete || warmupTicks < 100) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null) {
            fail("Unexpected screen after key dispatch during " + currentPhase + ": "
                    + mc.screen.getClass().getSimpleName());
            return;
        }
        if (!mc.player.getInventory().getItem(0).is(Items.IRON_SWORD)) {
            fail("Held sword was dropped/lost during " + currentPhase);
            return;
        }
        if (mc.options.keyDrop.consumeClick() || mc.options.keyInventory.consumeClick()) {
            fail("Vanilla Q/E click remained unconsumed after " + currentPhase);
        }
    }

    private static boolean initialize(Minecraft mc) {
        try {
            Path actual = mc.gameDirectory.toPath().toRealPath();
            Path expected = Path.of(System.getProperty("tacticalactions.visualTest.expectedGameDir", "")).toRealPath();
            if (!actual.equals(expected) || !actual.getFileName().toString().equals("run")) {
                disabled = true;
                LOGGER.error("[TacticalVisualTest] Refusing to modify any world outside project run: {}", actual);
                return false;
            }
            output = actual.resolve("visual-test");
            Files.createDirectories(output.resolve("screenshots"));
            Files.writeString(output.resolve("sequence.log"), "Tactical visual test started\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            initialized = true;
            mc.options.pauseOnLostFocus = false;
            // F1 also hides vanilla first-person hands, so preserve the HUD for accurate arm/item tests.
            mc.options.hideGui = false;
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            releaseInput();
            // Unspawned, invisible camera entity: a stable side view independent of the player's rotation.
            sideCamera = new ArmorStand(mc.level, X + 4, Y - 0.8, Z);
            sideCamera.xo = sideCamera.xOld = X + 4;
            sideCamera.yo = sideCamera.yOld = Y - 0.8;
            sideCamera.zo = sideCamera.zOld = Z;
            sideCamera.setYRot(90);
            sideCamera.yRotO = 90;
            sideCamera.setXRot(0);
            sideCamera.xRotO = 0;
            while (TacticalActionsKeyMappings.PRONE.consumeClick()) { /* discard old input */ }
            UUID playerId = mc.player.getUUID();
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> prepareStage(server, playerId));
            log("Preparing isolated fixed stage in " + actual);
            return true;
        } catch (IOException exception) {
            disabled = true;
            LOGGER.error("[TacticalVisualTest] Cannot initialize test output", exception);
            return false;
        }
    }

    private static void prepareStage(MinecraftServer server, UUID playerId) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("Test player disconnected");
            ServerLevel level = player.serverLevel();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.setDayTime(6000);
            level.setWeatherParameters(6000, 0, false, false);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    level.setBlock(pos.set(x, -61, z), ((x + z) % 2 == 0
                            ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState(), 3);
                    for (int y = -60; y <= -52; y++) {
                        level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            // Grid behind both first-person and front-view third-person shots makes roll/translation clear.
            for (int x = -10; x <= 10; x++) {
                for (int y = -60; y <= -53; y++) {
                    var block = x % 2 == 0 || y % 2 == 0 ? Blocks.GRAY_CONCRETE : Blocks.WHITE_CONCRETE;
                    level.setBlock(pos.set(x, y, 8), block.defaultBlockState(), 3);
                    level.setBlock(pos.set(x, y, -8), block.defaultBlockState(), 3);
                }
            }
            level.setBlock(pos.set(0, -61, 0), Blocks.RED_CONCRETE.defaultBlockState(), 3);
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            player.getInventory().selected = 0;
            player.getInventory().setChanged();
            player.teleportTo(level, X, Y, Z, 0, 0);
            player.setDeltaMovement(Vec3.ZERO);
            stageReady = true;
            log("STAGE READY: player at " + X + "," + Y + "," + Z);
        } catch (Throwable exception) {
            disabled = true;
            LOGGER.error("[TacticalVisualTest] Stage preparation failed", exception);
            log("FAILED: " + exception);
        }
    }

    private static void freezeView(Minecraft mc) {
        mc.player.setYRot(0);
        mc.player.yRotO = 0;
        mc.player.setYHeadRot(0);
        mc.player.yHeadRotO = 0;
        mc.player.setYBodyRot(0);
        mc.player.yBodyRotO = 0;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static void releaseInput() {
        KeyMapping.set(TacticalActionsKeyMappings.LEAN_LEFT.getKey(), false);
        KeyMapping.set(TacticalActionsKeyMappings.LEAN_RIGHT.getKey(), false);
        TacticalActionsKeyMappings.LEAN_LEFT.setDown(false);
        TacticalActionsKeyMappings.LEAN_RIGHT.setDown(false);
        TacticalActionsKeyMappings.PRONE.setDown(false);
    }

    private static void fail(String message) {
        disabled = true;
        releaseInput();
        CAPTURES.add("FAILED-" + currentPhase + ".png");
        log("FAILED: " + message);
    }

    private static synchronized void log(String message) {
        LOGGER.info("[TacticalVisualTest] {}", message);
        if (output != null) {
            try {
                Files.writeString(output.resolve("sequence.log"), message + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                LOGGER.warn("[TacticalVisualTest] Cannot write sequence log", exception);
            }
        }
    }

    private record Phase(String name, CameraType camera, boolean left, boolean right, boolean prone, float pitch,
                         boolean side) {
        Phase(String name, CameraType camera, boolean left, boolean right, boolean prone, float pitch) {
            this(name, camera, left, right, prone, pitch, false);
        }
    }
}
