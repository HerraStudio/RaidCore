package dev.herrastudio.raidcore.integration;

import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.raid.client.RaidEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;

import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Pixel-based occlusion checks in a separate game directory and a fresh, owned world. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidCoreEvacuationSmoke {
    private static boolean started;
    private static int ticks, phase, wait, mode;
    private static String capture;
    private static final List<String> RESULTS = new ArrayList<>();
    private static boolean occlusionPassed = true;
    private static final String LABEL = System.getProperty("raidcore.evacuationSmoke.label", "baseline");

    private RaidCoreEvacuationSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("raidcore.evacuationSmoke")) return;
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            require(mc.gameDirectory.toPath().toRealPath().equals(
                    Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()), "Smoke directory is not isolated");
            started = true;
            mc.getWindow().setWindowed(1200, 800);
            mc.options.renderDistance().set(3);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.graphicsMode().set(GraphicsStatus.FANCY);
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var settings = new LevelSettings("RaidCore smoke occlusion", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("evacuation-smoke-" + System.currentTimeMillis(), settings,
                    new WorldOptions(31, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started || mc.player == null || ticks == 0 && mc.screen != null) return;
        ticks++;
        wait++;
        require(ticks < 900, "Smoke harness stalled at " + phase);
        if (phase == 0 && wait >= 20) {
            require(IrisCompat.isUsingShaderPack() == Boolean.getBoolean("raidcore.evacuationSmoke.shaders"),
                    "Unexpected shader pack state");
            server(player -> {
                var level = player.serverLevel();
                level.setDayTime(6000);
                level.setWeatherParameters(20000, 0, false, false);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
                for (int x = -12; x <= 12; x++) for (int z = -8; z <= 12; z++)
                    level.setBlockAndUpdate(new BlockPos(x, 78, z), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int x = -12; x <= 12; x++) for (int y = 79; y <= 90; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, 11), Blocks.STONE.defaultBlockState());
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(level, .5, 79, -4, 0, 0);
            });
            next(1);
        } else if (phase == 1 && wait >= 40) {
            wall(false);
            smoke(6.5);
            next(2);
        } else if (phase == 2 && wait >= 100) {
            verifyRuntime();
            capture = "open";
            next(3);
        } else if (phase == 3 && wait >= 5) {
            wall(true);
            next(4);
        } else if (phase == 4 && wait >= 40) {
            verifyRuntime();
            capture = "blocked";
            next(5);
        } else if (phase == 5 && wait >= 5) {
            smoke(0);
            next(6);
        } else if (phase == 6 && wait >= 75) {
            verifyRuntime();
            capture = "foreground";
            next(7);
        } else if (phase == 7 && wait >= 5) {
            var previous = RaidEffects.activeRuntime("occlusion");
            RaidEffects.onResourceReload();
            RaidEffects.tick();
            require(RaidEffects.activeCount() == 1 && RaidEffects.activeRuntime("occlusion") != previous,
                    "Reload did not replace the effect exactly once");
            RaidEffects.clear();
            require(RaidEffects.activeCount() == 0, "Smoke cleanup leaked an effect");
            if (mode++ == 0 && !IrisCompat.isUsingShaderPack()) {
                mc.options.graphicsMode().set(GraphicsStatus.FABULOUS);
                mc.levelRenderer.allChanged();
                next(1);
            } else {
                RESULTS.add("occlusionPassed=" + occlusionPassed + "; irisInstalled=" + IrisCompat.isModInstalled()
                        + "; shaders=" + IrisCompat.isUsingShaderPack() + "; reloadAndCleanup=true");
                Files.write(mc.gameDirectory.toPath().resolve(LABEL + "-results.txt"), RESULTS);
                for (String result : RESULTS) LoggerFactory.getLogger("RaidCoreEvacuationSmoke").info(result);
                require(!Boolean.getBoolean("raidcore.evacuationSmoke.strict") || occlusionPassed,
                        "Smoke rendered through the wall");
                LoggerFactory.getLogger("RaidCoreEvacuationSmoke").info(occlusionPassed
                        ? "RAIDCORE_EVACUATION_SMOKE_PASS" : "RAIDCORE_EVACUATION_SMOKE_BASELINE_CAPTURED");
                mc.stop();
                next(8);
            }
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (capture == null) return;
        var mc = Minecraft.getInstance();
        try (var png = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int green = 0;
            for (int y = png.getHeight() / 4; y < png.getHeight() * 3 / 4; y++) {
                for (int x = png.getWidth() / 4; x < png.getWidth() * 3 / 4; x++) {
                    int color = png.getPixelRGBA(x, y);
                    int r = color & 255, g = color >> 8 & 255, b = color >> 16 & 255;
                    if (g > 80 && g > r * 1.6 + 30 && g > b * 1.4 + 30) green++;
                }
            }
            String name = LABEL + "-" + mc.options.graphicsMode().get().name().toLowerCase() + "-" + capture;
            png.writeToFile(mc.gameDirectory.toPath().resolve(name + ".png"));
            RESULTS.add(name + ": greenPixels=" + green + "; depthFunc=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            if (capture.equals("blocked")) occlusionPassed &= green == 0;
            else require(green > 100, "Smoke is not visible in " + name + ": " + green);
        }
        capture = null;
    }

    private static void smoke(double z) {
        RaidEffects.clear();
        RaidEffects.setZones(List.of(new RaidEffects.SmokeZone("occlusion", "minecraft:overworld", .5, 80.6, z)));
        RaidEffects.tick();
    }

    private static void verifyRuntime() {
        var runtime = RaidEffects.activeRuntime("occlusion");
        require(runtime != null && runtime.isValid(), "Live smoke runtime missing");
        var emitter = runtime.getFxData().objects().stream().filter(ParticleEmitter.class::isInstance)
                .map(ParticleEmitter.class::cast).findFirst().orElseThrow();
        require(emitter.getParticleAmount() > 0, "Smoke stopped emitting");
        require(emitter.config.getStartColor().get(0f, () -> .5f).intValue() == 0xff11ff00, "Authored smoke color changed");
    }

    private static void wall(boolean present) {
        server(player -> {
            for (int x = -8; x <= 8; x++) for (int y = 79; y <= 89; y++)
                player.serverLevel().setBlockAndUpdate(new BlockPos(x, y, 2),
                        (present ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        });
    }

    private static void server(Consumer<ServerPlayer> work) {
        var mc = Minecraft.getInstance();
        var id = mc.player.getUUID();
        mc.getSingleplayerServer().submit(() -> work.accept(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }

    private static void next(int nextPhase) {
        phase = nextPhase;
        wait = 0;
    }
}
