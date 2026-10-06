package dev.herrastudio.raidcore.integration;

import com.sgr792.gwo.client.render.GwoRenderTypes;
import com.sgr792.gwo.client.render.fx.BulletDecalManager;
import com.sgr792.gwo.content.GunDefinition.BodyDamageSettings;
import com.sgr792.gwo.entity.ModEntities;
import com.sgr792.gwo.entity.projectile.BulletEntity;
import com.sgr792.gwo.entity.projectile.ResolvedBallisticProfile;
import dev.herrastudio.raidcore.RaidCore;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Native A/B test: identical live decals with legacy cell sorting and stable paint order. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidCoreDecalOverlapSmoke {
    private static final ResourceLocation TEXTURE = RaidCoreSmokeChecks.id("gwo:textures/effects/bullet_decals.png");
    private static boolean started;
    private static int ticks, phase, wait;
    private static String capture;
    private static final StringBuilder RESULTS = new StringBuilder();

    private RaidCoreDecalOverlapSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("raidcore.decalOverlapSmoke")) return;
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            mc.getWindow().setWindowed(1440, 900);
            mc.options.renderDistance().set(4);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false;
            mc.resizeDisplay();
            var settings = new LevelSettings("RaidCore overlap regression", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("overlap-smoke-" + System.currentTimeMillis(), settings,
                    new WorldOptions(19, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started || mc.player == null || ticks == 0 && mc.screen != null) return;
        ticks++; wait++;
        require(ticks < 700, "Overlap harness stalled at " + phase);
        if (phase == 0 && wait >= 20) {
            RaidCoreClientSmoke.verifyBootstrap(mc);
            require(!GwoRenderTypes.getBulletDecal(TEXTURE).sortOnUpload(), "Bullet mesh cells are still sorted independently");
            require(GwoRenderTypes.getMuzzleFlash(TEXTURE).sortOnUpload(), "Muzzle flash ordering was changed");
            BulletDecalManager.clear();
            server(player -> {
                var level = player.serverLevel();
                level.setDayTime(1000);
                level.setWeatherParameters(20000, 0, false, false);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
                for (int x = -2; x <= 2; x++) {
                    for (int y = 79; y <= 83; y++) level.setBlockAndUpdate(new BlockPos(x, y, 0), Blocks.STONE.defaultBlockState());
                    for (int z = -6; z <= -2; z++) level.setBlockAndUpdate(new BlockPos(x, 78, z), Blocks.STONE.defaultBlockState());
                }
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(0.5, 78.98, -1.4);
                player.setYRot(0); player.setXRot(0);
                return true;
            });
            phase = 1; wait = 0;
        } else if (phase == 1 && wait >= 45) {
            server(player -> {
                var profile = new ResolvedBallisticProfile(10, 1, 0, 1, 1, 60, 100, 0, 0, 1.5F, 0.75F,
                        new BodyDamageSettings(1, 1, 1, 1), List.of(), 1, 1, 1);
                int[] angles = {75, 60, 89, 45, 75, 0};
                for (int i = 0; i < 6; i++) {
                    double radians = Math.toRadians(angles[i]);
                    shoot(player, profile, new Vec3(0.4 + i * 0.034, 80.6 + (i % 2) * 0.024, 0),
                            new Vec3(-Math.sin(radians), 0, Math.cos(radians)));
                    shoot(player, profile, new Vec3(0.4 + i * 0.034, 79, -3.6 + (i % 2) * 0.024),
                            new Vec3(Math.sin(radians) * 0.6, -Math.cos(radians), Math.sin(radians) * 0.8));
                }
                return true;
            });
            phase = 2; wait = 0;
        } else if (phase == 2 && wait >= 100) {
            require(decals().size() == 12, "Expected six overlapping wall and six overlapping floor impacts");
            sort(true); // Reproduce the released 1.0.1 render state only inside this isolated test JVM.
            phase = 3; wait = 0;
        } else if (phase == 3 && wait >= 6) {
            capture = "decal-overlap-wall-before.png";
            phase = 4; wait = 0;
        } else if (phase == 4 && wait >= 6) {
            sort(false);
            phase = 5; wait = 0;
        } else if (phase == 5 && wait >= 6) {
            capture = "decal-overlap-wall-after.png";
            phase = 6; wait = 0;
        } else if (phase == 6 && wait >= 6) {
            capture = "decal-overlap-wall-stable.png";
            phase = 7; wait = 0;
        } else if (phase == 7 && wait >= 6) {
            compare("wall");
            server(player -> { player.teleportTo(0.5, 81, -3.6); player.setXRot(90); return true; });
            phase = 8; wait = 0;
        } else if (phase == 8 && wait >= 20) {
            mc.player.setXRot(90);
            sort(true);
            phase = 9; wait = 0;
        } else if (phase == 9 && wait >= 6) {
            capture = "decal-overlap-floor-before.png";
            phase = 10; wait = 0;
        } else if (phase == 10 && wait >= 6) {
            sort(false);
            phase = 11; wait = 0;
        } else if (phase == 11 && wait >= 6) {
            capture = "decal-overlap-floor-after.png";
            phase = 12; wait = 0;
        } else if (phase == 12 && wait >= 6) {
            capture = "decal-overlap-floor-stable.png";
            phase = 13; wait = 0;
        } else if (phase == 13 && wait >= 6) {
            compare("floor");
            server(player -> { player.teleportTo(1, 78.98, -1.2); player.setYRot(22); player.setXRot(0); return true; });
            mc.player.setYRot(22); mc.player.yRotO = 22;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            phase = 14; wait = 0;
        } else if (phase == 14 && wait >= 20) {
            capture = "decal-overlap-wall-oblique.png";
            phase = 15; wait = 0;
        } else if (phase == 15 && wait >= 6) {
            require(!GwoRenderTypes.getBulletDecal(TEXTURE).sortOnUpload(), "Fixed ordering was not restored");
            Files.writeString(Path.of("decal-overlap-results.txt"), RESULTS);
            LoggerFactory.getLogger("RaidCoreOverlapSmoke").info("RAIDCORE_DECAL_OVERLAP_PASS: 12 real impacts, mixed round/mesh overlap, wall/floor native legacy-vs-fixed frames, fixed-frame stability, changed viewpoint, original muzzle sorting retained; {}", RESULTS);
            mc.stop();
        }
    }

    private static void shoot(ServerPlayer player, ResolvedBallisticProfile profile, Vec3 hit, Vec3 velocity) {
        var bullet = new BulletEntity(ModEntities.BULLET.get(), player.serverLevel());
        bullet.configure(player, profile, velocity.scale(0.5), false, 1, RaidCoreSmokeChecks.id("gwo:decal_test"), 1, 1, -1);
        bullet.setPos(hit.subtract(velocity.scale(0.2)));
        bullet.setDeltaMovement(velocity.scale(0.5));
        bullet.tick();
        require(bullet.isRemoved(), "Overlap fixture bullet missed");
    }

    private static void sort(boolean enabled) throws Exception {
        var field = RenderType.class.getDeclaredField("sortOnUpload");
        field.setAccessible(true);
        field.setBoolean(GwoRenderTypes.getBulletDecal(TEXTURE), enabled);
        require(GwoRenderTypes.getBulletDecal(TEXTURE).sortOnUpload() == enabled, "Native A/B render state did not change");
    }

    private static void compare(String surface) throws Exception {
        var before = javax.imageio.ImageIO.read(Path.of("decal-overlap-" + surface + "-before.png").toFile());
        var after = javax.imageio.ImageIO.read(Path.of("decal-overlap-" + surface + "-after.png").toFile());
        var stable = javax.imageio.ImageIO.read(Path.of("decal-overlap-" + surface + "-stable.png").toFile());
        int difference = 0, unstable = 0;
        for (int y = after.getHeight() / 4; y < after.getHeight() * 3 / 4; y++)
            for (int x = after.getWidth() / 4; x < after.getWidth() * 3 / 4; x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) difference++;
                if (after.getRGB(x, y) != stable.getRGB(x, y)) unstable++;
            }
        RESULTS.append(surface).append(": legacy-vs-fixed changed pixels=").append(difference)
                .append(", fixed-frame changed pixels=").append(unstable).append('\n');
        require(difference > 100, "Overlap regression did not reproduce on " + surface);
        require(unstable < 50, "Fixed overlap still changes at a stationary viewpoint on " + surface + ": " + unstable);
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (capture == null) return;
        try (var png = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { png.writeToFile(Path.of(capture)); }
        capture = null;
    }
    private static Deque<?> decals() throws Exception {
        var field = BulletDecalManager.class.getDeclaredField("ALL"); field.setAccessible(true); return (Deque<?>) field.get(null);
    }
    private static <T> T server(Function<ServerPlayer, T> action) {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().submit(() -> action.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }
}
