package dev.herrastudio.raidcore.integration;

import com.sgr792.gwo.client.render.fx.BulletDecalManager;
import com.sgr792.gwo.content.GunDefinition.BodyDamageSettings;
import com.sgr792.gwo.entity.ModEntities;
import com.sgr792.gwo.entity.projectile.BulletEntity;
import com.sgr792.gwo.entity.projectile.ResolvedBallisticProfile;
import com.sgr792.gwo.network.ModPayloads.BlockImpactPayload;
import dev.herrastudio.raidcore.RaidCore;
import dev.herrastudio.raidcore.network.ImpactDecalNetwork;
import dev.herrastudio.raidcore.ui.decal.ImpactProjection;
import dev.herrastudio.raidcore.ui.decal.ProjectedDecal;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Real server projectile -> optional wire channel -> native GWO decal -> framebuffer. */
@EventBusSubscriber(modid = RaidCore.MOD_ID, value = Dist.CLIENT)
public final class RaidCoreDecalSmoke {
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static int ticks, phase, wait, received;
    private static boolean started;
    private static String image;

    private RaidCoreDecalSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("raidcore.decalSmoke") || Boolean.getBoolean("raidcore.decalOverlapSmoke")) return;
        var mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            started = true;
            mc.getWindow().setWindowed(1440, 900);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(4);
            mc.options.pauseOnLostFocus = false;
            mc.options.hideGui = true;
            mc.resizeDisplay();
            var settings = new LevelSettings("RaidCore decal integration", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("decal-smoke-" + System.currentTimeMillis(), settings,
                    new WorldOptions(19, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
        }
        if (!started || mc.player == null || ticks == 0 && mc.screen != null) return;
        ticks++;
        wait++;
        require(ticks < 600, "Decal harness stalled at phase " + phase);
        if (phase == 0 && wait >= 20) {
            RaidCoreClientSmoke.verifyBootstrap(mc);
            require(mc.getConnection().hasChannel(ImpactDecalNetwork.ProjectedImpact.TYPE), "Impact direction channel missing");
            codec(mc);
            var original = ImpactDecalNetwork.clientReceiver;
            ImpactDecalNetwork.clientReceiver = payload -> {
                received++;
                try {
                    int before = decals().size();
                    original.accept(payload);
                    if (decals().size() != before + 1) log("DECAL_REJECTED: " + payload.impact());
                } catch (Exception error) { throw new IllegalStateException(error); }
            };
            fixtures();
            BulletDecalManager.clear();
            server(player -> {
                var level = player.serverLevel();
                level.setDayTime(1000);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
                for (int x = -4; x <= 4; x++) {
                    for (int y = 79; y <= 84; y++) level.setBlockAndUpdate(new BlockPos(x, y, 0), Blocks.TERRACOTTA.defaultBlockState());
                    for (int z = -8; z <= -2; z++) level.setBlockAndUpdate(new BlockPos(x, 78, z), Blocks.TERRACOTTA.defaultBlockState());
                }
                for (Shot shot : SHOTS) level.setBlockAndUpdate(shot.block, shot.state);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(0, 79.85, -3.5);
                player.setYRot(0);
                player.setXRot(0);
                return true;
            });
            phase = 1; wait = 0;
        } else if (phase == 1 && wait >= 50) {
            server(player -> {
                var profile = new ResolvedBallisticProfile(10, 1, 0, 1, 1, 60, 100, 0,
                        0, 1.5F, 0.75F, new BodyDamageSettings(1, 1, 1, 1), List.of(), 1, 1, 1);
                for (Shot shot : SHOTS) {
                    if (shot.legacy) {
                        var impact = BlockImpactPayload.create(shot.block, shot.hit, shot.face, 0, 0.072F,
                                91, Block.getId(shot.state));
                        PacketDistributor.sendToPlayersTrackingChunk(player.serverLevel(), new ChunkPos(shot.block), impact);
                        continue;
                    }
                    var bullet = new BulletEntity(ModEntities.BULLET.get(), player.serverLevel());
                    bullet.configure(player, profile, shot.velocity.scale(0.5), false, 1,
                            RaidCoreSmokeChecks.id("gwo:decal_test"), 1, 1, -1);
                    bullet.setPos(shot.hit.subtract(shot.velocity.scale(0.2)));
                    bullet.setDeltaMovement(shot.velocity.scale(0.5));
                    bullet.tick();
                    require(bullet.isRemoved(), "Bullet did not hit " + shot.name);
                }
                return true;
            });
            phase = 2; wait = 0;
        } else if (phase == 2 && wait >= 30) {
            verifyNativeDecals(mc);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            image = "decal-wall.png";
            phase = 3; wait = 0;
        } else if (phase == 3 && wait >= 8) {
            server(player -> { player.teleportTo(1.5, 79.85, -1.6); return true; });
            phase = 31; wait = 0;
        } else if (phase == 31 && wait >= 15) {
            image = "decal-wall-detail.png";
            phase = 32; wait = 0;
        } else if (phase == 32 && wait >= 8) {
            server(player -> { player.teleportTo(0, 82, -4.5); player.setXRot(90); return true; });
            phase = 4; wait = 0;
        } else if (phase == 4 && wait >= 15) {
            mc.player.setXRot(90);
            image = "decal-floor.png";
            phase = 5; wait = 0;
        } else if (phase == 5 && wait >= 8) {
            verifyLifecycle(mc);
            log("RAIDCORE_DECAL_CLIENT_PASS: real GWO projectile collision, direction packet, anchored hole, asymmetric forward rim/splat, six faces, 0/30/45/60/75/89 degrees, slab/edge fit, legacy round fallback, native texture/render, block removal and caps");
            mc.stop();
        }
    }

    private static void fixtures() {
        int[] angles = {0, 30, 45, 60, 75, 89};
        for (int i = 0; i < angles.length; i++) {
            double radians = Math.toRadians(angles[i]);
            Vec3 wallDirection = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
            Vec3 floorDirection = new Vec3(Math.sin(radians) * 0.6, -Math.cos(radians), Math.sin(radians) * 0.8);
            SHOTS.add(new Shot("wall-" + angles[i], new BlockPos(i - 3, 81, 0),
                    new Vec3(i - 2.5, 81.5, 0), Direction.NORTH, wallDirection, Blocks.TERRACOTTA.defaultBlockState(), false));
            SHOTS.add(new Shot("floor-" + angles[i], new BlockPos(i - 3, 78, -5),
                    new Vec3(i - 2.5, 79, -4.5), Direction.UP, floorDirection, Blocks.TERRACOTTA.defaultBlockState(), false));
        }
        var cube = new BlockPos(4, 80, -4);
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
            Vec3 tangent = face.getAxis() == Direction.Axis.X ? new Vec3(0, 0.6, 0.8)
                    : face.getAxis() == Direction.Axis.Y ? new Vec3(0.6, 0, 0.8) : new Vec3(0.6, 0.8, 0);
            SHOTS.add(new Shot("face-" + face, cube, Vec3.atCenterOf(cube).add(normal.scale(0.5)), face,
                    tangent.scale(Math.sin(Math.toRadians(75))).subtract(normal.scale(Math.cos(Math.toRadians(75)))),
                    Blocks.STONE.defaultBlockState(), false));
        }
        SHOTS.add(new Shot("slab-side", new BlockPos(4, 81, -6), new Vec3(4.5, 81.45, -6), Direction.NORTH,
                new Vec3(0, Math.sin(Math.toRadians(75)), Math.cos(Math.toRadians(75))), Blocks.STONE_SLAB.defaultBlockState(), false));
        SHOTS.add(new Shot("wall-edge", new BlockPos(-3, 82, 0), new Vec3(-2.06, 82.5, 0), Direction.NORTH,
                new Vec3(Math.sin(Math.toRadians(89)), 0, Math.cos(Math.toRadians(89))), Blocks.TERRACOTTA.defaultBlockState(), false));
        SHOTS.add(new Shot("legacy", new BlockPos(4, 82, -4), new Vec3(4.5, 82.5, -4), Direction.NORTH,
                new Vec3(1, 0, 0.1).normalize(), Blocks.OAK_PLANKS.defaultBlockState(), true));
    }

    private static void codec(Minecraft mc) {
        var original = BlockImpactPayload.create(new BlockPos(-3, 81, 0), new Vec3(-2.7, 81.5, 0),
                Direction.NORTH, 3, 0.072F, -12345, Block.getId(Blocks.TERRACOTTA.defaultBlockState()));
        var payload = ImpactDecalNetwork.ProjectedImpact.create(original, new Vec3(-3, 1, 4));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.level.registryAccess());
        try {
            BlockImpactPayload.STREAM_CODEC.encode(buffer, original);
            int legacySize = buffer.readableBytes();
            buffer.clear();
            ImpactDecalNetwork.ProjectedImpact.CODEC.encode(buffer, payload);
            require(buffer.readableBytes() == legacySize + 6, "Direction overhead differs from six bytes");
            require(ImpactDecalNetwork.ProjectedImpact.CODEC.decode(buffer).equals(payload) && buffer.readableBytes() == 0,
                    "Direction codec lost impact fields or signed direction");
        } finally { buffer.release(); }
        log("RAIDCORE_DECAL_CODEC_PASS: original impact fields preserved, signed direction round trip, six bytes overhead");
    }

    private static void verifyNativeDecals(Minecraft mc) throws Exception {
        Deque<?> decals = decals();
        require(received == SHOTS.size() - 1, "Direction packet count: " + received);
        for (Shot shot : SHOTS) {
            if (decals.stream().noneMatch(d -> shot.block.equals(field(d, "blockPos")) && shot.face == field(d, "face"))) {
                log("DECAL_MISSING: " + shot.name + " block=" + shot.block + " face=" + shot.face);
            }
        }
        require(decals.size() == SHOTS.size(), "Missing or duplicate decals: " + decals.size());
        var evidence = new StringBuilder("name\tface\tentry_position_fraction\thole_anchor_error\tmesh_vertices\n");
        for (Shot shot : SHOTS) {
            Object decal = decals.stream().filter(d -> shot.block.equals(field(d, "blockPos")) && shot.face == field(d, "face")).findFirst().orElseThrow();
            var mesh = ((ProjectedDecal) decal).raidcore$mesh();
            float[] vertices = mesh == null ? (float[]) field(decal, "vertices") : mesh.positions();
            double[] center = {(double) field(decal, "centerX"), (double) field(decal, "centerY"), (double) field(decal, "centerZ")};
            int axis = shot.face.getAxis() == Direction.Axis.X ? 0 : shot.face.getAxis() == Direction.Axis.Y ? 1 : 2;
            int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
            double uu = 0, vv = 0, uv = 0, minAlong = Double.POSITIVE_INFINITY, maxAlong = Double.NEGATIVE_INFINITY;
            var projection = ImpactProjection.fromVelocity(shot.velocity.x, shot.velocity.y, shot.velocity.z, axis);
            AABB bounds = shot.state.getCollisionShape(mc.level, shot.block).bounds().move(shot.block);
            double[] mins = {bounds.minX, bounds.minY, bounds.minZ}, maxs = {bounds.maxX, bounds.maxY, bounds.maxZ};
            for (int i = 0; i < vertices.length / 3; i++) {
                for (int component = 0; component < 3; component++) require(Float.isFinite(vertices[i * 3 + component]), "Nonfinite vertex");
                require(Math.abs(vertices[i * 3 + axis] - center[axis]) < 1.0e-5, "Decal left its face plane");
                double du = vertices[i * 3 + u] - center[u], dv = vertices[i * 3 + v] - center[v];
                uu += du * du; vv += dv * dv; uv += du * dv;
                double along = du * projection.tangentU() + dv * projection.tangentV();
                minAlong = Math.min(minAlong, along); maxAlong = Math.max(maxAlong, along);
                require(vertices[i * 3 + u] >= mins[u] - 1.0e-5 && vertices[i * 3 + u] <= maxs[u] + 1.0e-5
                        && vertices[i * 3 + v] >= mins[v] - 1.0e-5 && vertices[i * 3 + v] <= maxs[v] + 1.0e-5,
                        "Decal leaked outside supporting surface: " + shot.name);
            }
            double anchorError = 0;
            if (!shot.legacy && projection.stretch() > 1.01) {
                require(mesh != null, "Nonlinear impact mesh missing " + shot.name);
                int centerIndex = (vertices.length / 3 / 2) * 3;
                for (int component = 0; component < 3; component++) anchorError = Math.max(anchorError,
                        Math.abs(vertices[centerIndex + component] - center[component]));
                require(anchorError < 1.0e-5, "UV hole center moved away from the true hit " + shot.name);
                require(maxAlong > -minAlong + 1.0e-5, "Damage must extend along the incoming bullet's tangent " + shot.name);
                if (projection.stretch() >= 2) require(-minAlong / (maxAlong - minAlong) < 0.4,
                        "Entry hole remained centered in the damage " + shot.name);
            } else {
                require(mesh == null, "Frontal or legacy impact must retain the native quad " + shot.name);
                double difference = Math.hypot(uu - vv, 2 * uv);
                double aspect = Math.sqrt((uu + vv + difference) / (uu + vv - difference));
                require(Math.abs(aspect - 1) < 0.003, "Round fallback changed " + shot.name);
            }
            evidence.append(shot.name).append('\t').append(shot.face).append('\t')
                    .append(-minAlong / (maxAlong - minAlong)).append('\t').append(anchorError).append('\t')
                    .append(vertices.length / 3).append('\n');
        }
        Files.writeString(Path.of("decal-geometry.tsv"), evidence);
        log("RAIDCORE_DECAL_GEOMETRY_PASS: " + decals.size() + " real decals; UV hole center fixed to true hit, asymmetric forward damage along incoming velocity, face coplanarity, finite mesh vertices and voxel bounds");
    }

    private static void verifyLifecycle(Minecraft mc) throws Exception {
        Shot first = SHOTS.getFirst();
        server(player -> { player.serverLevel().setBlockAndUpdate(first.block, Blocks.AIR.defaultBlockState()); return true; });
        mc.level.setBlock(first.block, Blocks.AIR.defaultBlockState(), 3);
        mc.level.setGameTime(mc.level.getGameTime() + 100);
        BulletDecalManager.tick();
        require(decals().stream().noneMatch(d -> first.block.equals(field(d, "blockPos"))), "Removed block kept its decal");
        Shot legacy = SHOTS.getLast();
        for (int i = 0; i < 12; i++) BulletDecalManager.accept(BlockImpactPayload.create(legacy.block, legacy.hit,
                legacy.face, 0, 0.072F, i, Block.getId(legacy.state)));
        require(decals().stream().filter(d -> legacy.block.equals(field(d, "blockPos"))).count() == 6, "GWO face cap changed");
        mc.level.setGameTime(mc.level.getGameTime() + 2000);
        BulletDecalManager.tick();
        require(decals().isEmpty(), "GWO lifetime cleanup changed");
        log("RAIDCORE_DECAL_LIFECYCLE_PASS: block validation, six per face and original expiry");
    }

    private static Deque<?> decals() throws Exception {
        Field all = BulletDecalManager.class.getDeclaredField("ALL");
        all.setAccessible(true);
        return (Deque<?>) all.get(null);
    }

    private static Object field(Object object, String name) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) throws Exception {
        if (image == null) return;
        try (var png = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { png.writeToFile(Path.of(image)); }
        image = null;
    }

    private static <T> T server(Function<ServerPlayer, T> action) {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().submit(() -> action.apply(mc.getSingleplayerServer().getPlayerList()
                .getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String message) { LoggerFactory.getLogger("RaidCoreDecalSmoke").info(message); }
    private record Shot(String name, BlockPos block, Vec3 hit, Direction face, Vec3 velocity, BlockState state, boolean legacy) {}
}
