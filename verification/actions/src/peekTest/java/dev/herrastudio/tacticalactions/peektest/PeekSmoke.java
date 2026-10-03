package dev.herrastudio.tacticalactions.peektest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import dev.herrastudio.tacticalactions.TacticalActions;
import dev.herrastudio.tacticalactions.TacticalActionsClient;
import dev.herrastudio.tacticalactions.TacticalActionsKeyMappings;
import dev.herrastudio.tacticalactions.PeekStates;
import dev.herrastudio.tacticalactions.PeekPoseMath;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in native client/server regression. Its world and assets are never part of release jars. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class PeekSmoke {
    private enum Phase { SETTLE, LEFT, RIGHT, BOTH, RETURN, MENU, MENU_RETURN, WALL,
        WALL_RETURN, THIRD_LEFT, THIRD_RIGHT, THIRD_RETURN, GWO_SYNC, GWO_LEFT,
        GWO_ADS, GWO_FIRE, GWO_THIRD_LEFT, GWO_THIRD_RIGHT, GWO_THIRD_AUTHORED, GWO_RETURN, DONE }

    private static final ResourceLocation PEEK = ResourceLocation.fromNamespaceAndPath(TacticalActions.MOD_ID, "peek");
    private static final ResourceLocation GUN = ResourceLocation.parse("tacticalpeekqa:rifle");
    private static final double EPS = .00001;
    // xmax=.99 leaves the feet/body outside the test wall at x=1.
    private static final Vec3 STAGE = new Vec3(.69, -60, .5);
    private static final Queue<String> CAPTURES = new ArrayDeque<>();
    private static final AtomicInteger BULLETS = new AtomicInteger();
    private static final StringBuilder FRAMES = new StringBuilder("frame,phase,lean,camera_dx,camera_dy,roll,player_x,player_y,player_z\n");
    private static boolean initialized, stopped, gwo, gunInstalled, rendererProbed;
    private static volatile boolean stageReady;
    private static Path output;
    private static UUID playerId;
    private static Phase phase = Phase.SETTLE;
    private static int phaseTick, ticks, checks, frameCount, freeCameraSamples, wallCameraSamples;
    private static double freeCameraDistance, wallCameraDistance;
    private static Snapshot clientBaseline, serverBaseline;
    private static long deadline, gunRenderCountBaseline;
    private static long gunThirdRenderCountBaseline;
    private static PartState neutralLeftLeg, neutralRightLeg;
    private static boolean thirdRendererProbed;
    private static boolean authoredRendererProbed;
    private static Object gunDefinition;
    private static volatile double bulletLateralOffset;

    private PeekSmoke() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        if (!Boolean.getBoolean("tacticalactions.peekTest") || stopped) return;
        try {
            var mc = Minecraft.getInstance();
            if (!initialized) {
                if (mc.player == null || mc.getSingleplayerServer() == null) return;
                initialize(mc);
            }
            check(System.currentTimeMillis() < deadline, "runtime probe did not time out in " + phase);
            if (!stageReady || mc.player == null || mc.level == null) return;
            // The synthetic menu remains open for a few real ticks so screen reset is exercised.
            if (mc.screen != null && phase != Phase.MENU) return;
            freezeView(mc);
            phaseTick++;
            ticks++;
            if (phaseTick == 1) enter(mc);
            if (phase == Phase.MENU && phaseTick == 8) {
                releaseInput(mc);
                mc.setScreen(null);
            }
            if (phase == Phase.GWO_FIRE && phaseTick == 2) mc.options.keyAttack.setDown(false);
        } catch (Throwable error) { fail(error); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("tacticalactions.peekTest") || stopped || !stageReady || phaseTick == 0) return;
        try {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.getSingleplayerServer() == null) return;
            if (phase == Phase.SETTLE) {
                if (phaseTick < 70 || mc.screen != null) return;
                clientBaseline = Snapshot.of(mc.player);
                serverBaseline = server(mc, Snapshot::of);
                check(clientBaseline.position().distanceTo(STAGE) < .01, "fixed test stage initialized");
                change(Phase.LEFT);
                return;
            }
            invariant(mc);
            if (phase == Phase.LEFT && phaseTick <= 4) {
                float[] originalEntry = {.578125f, .875f, .984375f, 1f};
                float expectedCurrent = originalEntry[phaseTick - 1];
                float expectedPrevious = phaseTick == 1 ? 0 : originalEntry[phaseTick - 2];
                check(Math.abs(PeekStates.current(mc.player) - expectedCurrent) < .000002f,
                        "GD656 angle advances exactly once on client tick " + phaseTick);
                check(Math.abs(PeekStates.leanAt(mc.player, 0) - expectedPrevious) < .000002f,
                        "client angle history is independent of integrated-server entity ID on tick " + phaseTick);
                check(Math.abs(PeekStates.offsetAt(mc.player, 1) - expectedCurrent) < .000002f,
                        "GD656 offset advances exactly once on client tick " + phaseTick);
                check(Math.abs(PeekStates.offsetAt(mc.player, 0) - expectedPrevious) < .000002f,
                        "client offset history is independent of integrated server on tick " + phaseTick);
            }
            if (phaseTick == 20 && phase != Phase.GWO_SYNC && phase != Phase.GWO_FIRE && phase != Phase.DONE) {
                int expected = switch (phase) {
                    case LEFT, THIRD_LEFT, GWO_LEFT, GWO_ADS, GWO_THIRD_LEFT, GWO_THIRD_AUTHORED -> 1;
                    case RIGHT, THIRD_RIGHT, GWO_THIRD_RIGHT -> -1;
                    default -> 0;
                };
                if (phase != Phase.WALL) checkPoseAndServer(mc, expected);
            }
            if (phaseTick == 28 && phase != Phase.MENU && phase != Phase.GWO_SYNC) {
                CAPTURES.add(phase.name().toLowerCase() + ".png");
            }
            switch (phase) {
                case LEFT -> { if (phaseTick >= 34) {
                    check(freeCameraSamples > 0 && freeCameraDistance > .1, "real first-person camera moves for left peek");
                    change(Phase.RIGHT);
                } }
                case RIGHT -> { if (phaseTick >= 34) change(Phase.BOTH); }
                case BOTH -> { if (phaseTick >= 34) change(Phase.RETURN); }
                case RETURN -> { if (phaseTick >= 34) change(Phase.MENU); }
                case MENU -> { if (phaseTick >= 24) change(Phase.MENU_RETURN); }
                case MENU_RETURN -> { if (phaseTick >= 34) { wall(mc, true); change(Phase.WALL); } }
                case WALL -> { if (phaseTick >= 34) {
                    check(wallCameraSamples > 0, "wall case produced actual rendered camera samples");
                    check(wallCameraDistance < freeCameraDistance - .02,
                            "solid wall limits camera movement: free=" + freeCameraDistance + " wall=" + wallCameraDistance);
                    wall(mc, false);
                    change(Phase.WALL_RETURN);
                } }
                case WALL_RETURN -> { if (phaseTick >= 34) change(Phase.THIRD_LEFT); }
                case THIRD_LEFT -> { if (phaseTick >= 34) change(Phase.THIRD_RIGHT); }
                case THIRD_RIGHT -> { if (phaseTick >= 34) change(Phase.THIRD_RETURN); }
                case THIRD_RETURN -> { if (phaseTick >= 34) change(gwo ? Phase.GWO_SYNC : Phase.DONE); }
                case GWO_SYNC -> { if (phaseTick >= 45) {
                    check(isGun(mc.player.getMainHandItem()), "native server-created GWO gun reaches client inventory");
                    check(server(mc, p -> isGunUnchecked(p.getMainHandItem())), "native gun exists on actual server");
                    gunRenderCountBaseline = diagnostics("firstPersonApplications");
                    var neutral = gunPlayerModel(mc);
                    neutralLeftLeg = PartState.of(neutral.leftLeg);
                    neutralRightLeg = PartState.of(neutral.rightLeg);
                    change(Phase.GWO_LEFT);
                } }
                case GWO_LEFT -> { if (phaseTick >= 34) {
                    check(rendererProbed, "actual GWO renderFirstPersonCameraSpace entry was invoked");
                    check(diagnostics("firstPersonApplications") > gunRenderCountBaseline,
                            "GWO renderer mixin applied the peek transform through its actual wrapper");
                    double applied = ((Number) support().getMethod("lastFirstPersonLean").invoke(null)).doubleValue();
                    check(applied > .8, "GWO renderer uses left lean, not a stale or double inverted value");
                    change(Phase.GWO_ADS);
                } }
                case GWO_ADS -> { if (phaseTick >= 34) {
                    check((boolean) support().getMethod("isAiming").invoke(null), "real GWO use key enters ADS");
                    change(Phase.GWO_FIRE);
                } }
                case GWO_FIRE -> { if (phaseTick >= 25) {
                    check(BULLETS.get() > 0, "native GWO fire input spawns an actual server bullet");
                    check(bulletLateralOffset > .1, "server bullet originates at peeked eye instead of body center: " + bulletLateralOffset);
                    change(Phase.GWO_THIRD_LEFT);
                } }
                case GWO_THIRD_LEFT, GWO_THIRD_RIGHT -> { if (phaseTick >= 34) {
                    check(thirdRendererProbed, "actual PlayerModel.setupAnim reached GWO's native arm hook");
                    check(diagnostics("thirdPersonApplications") > gunThirdRenderCountBaseline,
                            "native GWO third-person arm hook adds GD656 pose during " + phase);
                    float applied = ((Number) support().getMethod("lastThirdPersonLean").invoke(null)).floatValue();
                    int sign = phase == Phase.GWO_THIRD_LEFT ? 1 : -1;
                    check(applied * sign > .8, "native third-person arm hook follows requested side");
                    change(phase == Phase.GWO_THIRD_LEFT ? Phase.GWO_THIRD_RIGHT : Phase.GWO_THIRD_AUTHORED);
                } }
                case GWO_THIRD_AUTHORED -> { if (phaseTick >= 34) {
                    check(authoredRendererProbed, "authored native GWO arm animation and GD656 pose were both validated");
                    check(diagnostics("thirdPersonApplications") > gunThirdRenderCountBaseline,
                            "enabled authored third-person native path reaches peek hook");
                    change(Phase.GWO_RETURN);
                } }
                case GWO_RETURN -> { if (phaseTick >= 34) change(Phase.DONE); }
                case DONE -> finish(mc);
                default -> { }
            }
        } catch (Throwable error) { fail(error); }
    }

    private static void initialize(Minecraft mc) throws Exception {
        Path actual = mc.gameDirectory.toPath().toRealPath();
        String configured = System.getProperty("tacticalactions.peekTest.expectedGameDir",
                System.getProperty("tacticalactions.visualTest.expectedGameDir", ""));
        check(actual.equals(Path.of(configured).toRealPath())
                && actual.getFileName().toString().equals("run-peek-smoke"), "test only touches isolated run-peek-smoke");
        initialized = true;
        deadline = System.currentTimeMillis() + 150_000;
        output = actual.resolve("peek-smoke");
        Files.createDirectories(output.resolve("screenshots"));
        gwo = ModList.get().isLoaded("gwo");
        playerId = mc.player.getUUID();
        mc.options.pauseOnLostFocus = false;
        mc.options.bobView().set(false);
        mc.options.autoJump().set(false);
        mc.options.hideGui = false;
        mc.options.fov().set(70);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        releaseInput(mc);
        var integrated = mc.getSingleplayerServer();
        integrated.submit(() -> {
            var p = player(mc);
            var level = p.serverLevel();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, integrated);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, integrated);
            level.setDayTime(6000);
            level.setWeatherParameters(6000, 0, false, false);
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(new BlockPos(x, -61, z), ((x + z) % 2 == 0
                        ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
                for (int y = -60; y <= -53; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.getInventory().clearContent();
            p.getInventory().selected = 0;
            p.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            p.inventoryMenu.broadcastChanges();
            p.teleportTo(level, STAGE.x, STAGE.y, STAGE.z, 0, 0);
            stageReady = true;
        }).join();
    }

    private static void enter(Minecraft mc) throws Exception {
        releaseInput(mc);
        mc.options.setCameraType(switch (phase) {
            case THIRD_LEFT, GWO_THIRD_LEFT, GWO_THIRD_AUTHORED -> CameraType.THIRD_PERSON_FRONT;
            case THIRD_RIGHT, THIRD_RETURN, GWO_THIRD_RIGHT -> CameraType.THIRD_PERSON_BACK;
            default -> CameraType.FIRST_PERSON;
        });
        switch (phase) {
            case LEFT, WALL, THIRD_LEFT, GWO_LEFT, GWO_ADS, GWO_FIRE, GWO_THIRD_LEFT, GWO_THIRD_AUTHORED -> key(true, false);
            case RIGHT, THIRD_RIGHT, GWO_THIRD_RIGHT -> key(false, true);
            case BOTH -> key(true, true);
            case MENU -> mc.setScreen(new Screen(Component.literal("Peek reset probe")) {
                @Override public boolean isPauseScreen() { return false; }
            });
            case GWO_SYNC -> installGun(mc);
            default -> { }
        }
        if (phase == Phase.GWO_ADS || phase == Phase.GWO_FIRE) mc.options.keyUse.setDown(true);
        if (phase == Phase.GWO_FIRE) {
            mc.options.keyAttack.setDown(true);
            KeyMapping.click(mc.options.keyAttack.getKey());
        }
        if (phase == Phase.GWO_THIRD_LEFT || phase == Phase.GWO_THIRD_RIGHT) {
            gunThirdRenderCountBaseline = diagnostics("thirdPersonApplications");
            thirdRendererProbed = false;
        }
        if (phase == Phase.GWO_THIRD_AUTHORED) {
            installAuthoredGunPose();
            gunThirdRenderCountBaseline = diagnostics("thirdPersonApplications");
        }
        LoggerFactory.getLogger("PeekSmoke").info("PEEK_SMOKE_BEGIN {}", phase);
    }

    private static void invariant(Minecraft mc) {
        Snapshot current = Snapshot.of(mc.player);
        check(current.matches(clientBaseline), "client position and collision box stay anchored during " + phase);
        check(server(mc, p -> Snapshot.of(p).matches(serverBaseline)),
                "server position and collision box stay anchored during " + phase);
        if (!gunInstalled) check(mc.player.getMainHandItem().is(Items.IRON_SWORD), "Q must not drop the test sword");
        check(mc.screen == null || phase == Phase.MENU, "E must not open inventory");
        check(!mc.options.keyDrop.consumeClick() && !mc.options.keyInventory.consumeClick(),
                "shared Q/E vanilla clicks were consumed");
    }

    private static void checkPoseAndServer(Minecraft mc, int sign) {
        float lean = TacticalActionsClient.leanAt(1);
        var layer = PlayerAnimationAccess.getPlayerAnimationLayer(mc.player, PEEK);
        check(layer != null, "new peek PAL layer is registered");
        var head = layer.get3DTransform(new PlayerAnimBone("head"));
        if (sign == 0) {
            check(Math.abs(lean) < .02 && Math.abs(head.positionX) < .05, "neutral/release has no residual lean in " + phase);
        } else {
            check(lean * sign > .85 && head.positionX * sign > .5,
                    "head and live lean follow requested direction in " + phase);
        }
        for (String bone : new String[]{"body", "left_leg", "right_leg"}) {
            var original = new PlayerAnimBone(bone);
            original.positionX = 1.25f; original.positionY = -2.5f; original.positionZ = .75f;
            original.rotX = .12f; original.rotY = -.08f; original.rotZ = .21f;
            var unchanged = layer.get3DTransform(original);
            check(Math.abs(unchanged.positionX - 1.25f) < EPS && Math.abs(unchanged.positionY + 2.5f) < EPS
                            && Math.abs(unchanged.positionZ - .75f) < EPS && Math.abs(unchanged.rotX - .12f) < EPS
                            && Math.abs(unchanged.rotY + .08f) < EPS && Math.abs(unchanged.rotZ - .21f) < EPS,
                    "root/leg original animation is retained: " + bone);
        }
        float serverLean = server(mc, p -> TacticalActionsClient.leanAt(p, 1));
        if (sign == 0) check(Math.abs(serverLean) < .1, "server receives neutral state in " + phase);
        else check(serverLean * sign > .8, "server receives same peek direction in " + phase);
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("tacticalactions.peekTest") || stopped || clientBaseline == null) return;
        try {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            var camera = mc.gameRenderer.getMainCamera();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 rawEye = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
            Vec3 delta = camera.getPosition().subtract(rawEye);
            if (mc.options.getCameraType().isFirstPerson() && mc.screen == null && phaseTick > 18
                    && phase != Phase.GWO_FIRE && phase != Phase.GWO_SYNC) {
                check(camera.getPosition().distanceTo(mc.player.getEyePosition(partial)) < .025,
                        "rendered camera and virtual shooting eye agree, without applying the offset twice in " + phase);
            }
            if (phase == Phase.LEFT && phaseTick > 18) {
                freeCameraDistance = Math.max(freeCameraDistance, delta.x);
                freeCameraSamples++;
            }
            if (phase == Phase.WALL && phaseTick > 18) {
                wallCameraDistance = Math.max(wallCameraDistance, delta.x);
                wallCameraSamples++;
            }
            if (phase == Phase.GWO_LEFT && phaseTick > 18 && !rendererProbed) probeNativeGunRenderer(mc);
            if ((phase == Phase.GWO_THIRD_LEFT || phase == Phase.GWO_THIRD_RIGHT)
                    && phaseTick > 18 && !thirdRendererProbed) {
                var model = gunPlayerModel(mc);
                check(neutralLeftLeg.matches(model.leftLeg) && neutralRightLeg.matches(model.rightLeg),
                        "GWO third-person root/legs stay in place while upper arms lean");
                thirdRendererProbed = true;
            }
            if (phase == Phase.GWO_THIRD_AUTHORED && phaseTick > 18 && !authoredRendererProbed) {
                probeAuthoredArms(mc);
                authoredRendererProbed = true;
            }
            FRAMES.append(frameCount++).append(',').append(phase).append(',').append(TacticalActionsClient.leanAt(partial))
                    .append(',').append(delta.x).append(',').append(delta.y).append(',').append(camera.getRoll())
                    .append(',').append(mc.player.getX()).append(',').append(mc.player.getY()).append(',').append(mc.player.getZ()).append('\n');
            if (!CAPTURES.isEmpty()) {
                String name = CAPTURES.remove();
                Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(),
                        message -> LoggerFactory.getLogger("PeekSmoke").info("PEEK_SMOKE_CAPTURE {}", name));
            }
        } catch (Throwable error) { fail(error); }
    }

    private static void probeNativeGunRenderer(Minecraft mc) throws Exception {
        // A no-model fixture still enters the actual GWO renderer wrapper. This verifies injection
        // without claiming to inspect protected gun graphics or directly calling our math helper.
        Class<?> rendererType = type("client.GltfGunRenderer");
        Object renderer = rendererType.getConstructor().newInstance();
        Method render = rendererType.getMethod("renderFirstPersonCameraSpace", ItemStack.class,
                PoseStack.class, net.minecraft.client.renderer.MultiBufferSource.class,
                int.class, int.class, float.class);
        PoseStack stack = new PoseStack();
        Matrix4f before = new Matrix4f(stack.last().pose());
        render.invoke(renderer, mc.player.getMainHandItem(), stack, mc.renderBuffers().bufferSource(), 15728880, 0, 1f);
        check(before.equals(stack.last().pose(), .00001f), "GWO wrapper restores PoseStack after rendering");
        rendererProbed = true;
    }

    private static PlayerModel<net.minecraft.client.player.AbstractClientPlayer> gunPlayerModel(Minecraft mc) {
        var model = new PlayerModel<net.minecraft.client.player.AbstractClientPlayer>(
                mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        // This invokes PAL and GWO's actual vanilla model mixins; never call our arm helper directly.
        model.setupAnim(mc.player, 0, 0, mc.player.tickCount, 0, 0);
        return model;
    }

    private static void installAuthoredGunPose() throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(output.resolve("synthetic-gun.json"))).getAsJsonObject();
        json.add("third_person_pose", JsonParser.parseString("""
                {"enabled":true,
                 "right_arm":{"lowered_rotation":[-35,12,7],"raised_rotation":[-35,12,7]},
                 "left_arm":{"lowered_rotation":[-20,-10,-4],"raised_rotation":[-20,-10,-4]}}
                """).getAsJsonObject());
        Class<?> definition = type("content.GunDefinition");
        gunDefinition = definition.getMethod("fromJson", JsonObject.class).invoke(null, json);
        Object firearm = type("content.FirearmDefinition").getConstructor(definition).newInstance(gunDefinition);
        type("content.WeaponContentRegistry").getMethod("installBootstrapDefinitions", Map.class)
                .invoke(null, Map.of(GUN, firearm));
        Files.writeString(output.resolve("synthetic-gun-authored.json"), json.toString());
    }

    private static void probeAuthoredArms(Minecraft mc) throws Exception {
        var model = new PlayerModel<net.minecraft.client.player.AbstractClientPlayer>(
                mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        Object settings = gunDefinition.getClass().getMethod("thirdPersonPose").invoke(gunDefinition);
        check((boolean) settings.getClass().getMethod("enabled").invoke(settings), "authored gun pose is enabled in actual GWO definition");
        Class<?> applier = type("client.thirdperson.ThirdPersonPlayerArmPoseApplier");
        // Use native apply to select its raised arm pose, then actual PlayerModel.setupAnim dispatch.
        applier.getMethod("apply", UUID.class, PlayerModel.class, settings.getClass(), float.class)
                .invoke(null, playerId, model, settings, 1f);
        model.setupAnim(mc.player, 0, 0, mc.player.tickCount, 0, 0);
        var right = PeekPoseMath.delta("right_arm", 1, 1);
        var left = PeekPoseMath.delta("left_arm", 1, 1);
        check(Math.abs(model.rightArm.xRot - ((float) Math.toRadians(-35) + right.rx())) < .00001f
                        && Math.abs(model.rightArm.yRot - ((float) Math.toRadians(12) + right.ry())) < .00001f
                        && Math.abs(model.rightArm.zRot - ((float) Math.toRadians(7) + right.rz())) < .00001f,
                "native authored right arm rotation is retained with GD656 pose added exactly once");
        check(Math.abs(model.leftArm.xRot - ((float) Math.toRadians(-20) + left.rx())) < .00001f
                        && Math.abs(model.leftArm.yRot - ((float) Math.toRadians(-10) + left.ry())) < .00001f
                        && Math.abs(model.leftArm.zRot - ((float) Math.toRadians(-4) + left.rz())) < .00001f,
                "native authored left arm rotation is retained with GD656 pose added exactly once");
        check(neutralLeftLeg.matches(model.leftLeg) && neutralRightLeg.matches(model.rightLeg),
                "native authored gun pose leaves both legs anchored");
        check(PartState.of(model.leftArm).matches(model.leftSleeve)
                        && PartState.of(model.rightArm).matches(model.rightSleeve), "native sleeves track the combined gun/peek arms");
        applier.getMethod("clear").invoke(null);
    }

    private static void installGun(Minecraft mc) throws Exception {
        String fixture = """
                {"display_name":"Native GWO peek QA","weapon_type":"firearm","magazine_size":30,
                 "damage":8,"range":96,"fire_modes":["semi"],
                 "mechanics":{"rpm":600,"fire_interval_ms":100},
                 "ballistics":{"muzzle_velocity":100,"gravity":0,"life_seconds":2.5},
                 "animation_controller":{"channels":{
                   "raise":{"clip":"raise","layer":"hand_action","duration":0.2},
                   "fire":{"clip":"fire","layer":"recoil","duration":0.08,"lock_fire":false},
                   "idle":{"clip":"idle","layer":"base","loop":true}}},
                 "animation_machine":{"version":2,"actions":{
                   "raise":{"type":"finite","default_state":"raise"},
                   "fire":{"type":"finite","default_state":"fire","pre_fire_mode":"presentation"}},"interrupts":[]},
                 "bullet_tracer":{"enabled":false},"first_person_arms":false}
                """;
        JsonObject json = JsonParser.parseString(fixture).getAsJsonObject();
        Class<?> definition = type("content.GunDefinition");
        gunDefinition = definition.getMethod("fromJson", JsonObject.class).invoke(null, json);
        Object firearm = type("content.FirearmDefinition").getConstructor(definition).newInstance(gunDefinition);
        type("content.WeaponContentRegistry").getMethod("installBootstrapDefinitions", Map.class)
                .invoke(null, Map.of(GUN, firearm));
        Files.writeString(output.resolve("synthetic-gun.json"), fixture);
        mc.getSingleplayerServer().submit(() -> {
            try {
                var p = player(mc);
                ItemStack gun = (ItemStack) type("GwoMod").getMethod("weaponStack", ResourceLocation.class).invoke(null, GUN);
                type("item.GunData").getMethod("initialize", ItemStack.class, ResourceLocation.class, definition)
                        .invoke(null, gun, GUN, gunDefinition);
                type("item.GunData").getMethod("setAmmo", ItemStack.class, int.class).invoke(null, gun, 30);
                p.getInventory().setItem(0, gun);
                p.inventoryMenu.broadcastChanges();
            } catch (Exception e) { throw new RuntimeException(e); }
        }).join();
        gunInstalled = true;
    }

    @SubscribeEvent
    public static void onBullet(EntityJoinLevelEvent event) {
        if (!Boolean.getBoolean("tacticalactions.peekTest") || stopped || event.getLevel().isClientSide()
                || !BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).equals(ResourceLocation.parse("gwo:bullet"))) return;
        try {
            Object shooter = event.getEntity().getClass().getMethod("getShooter").invoke(event.getEntity());
            if (shooter instanceof ServerPlayer p && p.getUUID().equals(playerId)) {
                Vec3 rawEye = new Vec3(p.getX(), p.getEyeY(), p.getZ());
                Vec3 offset = event.getEntity().position().subtract(rawEye);
                double yaw = Math.toRadians(p.getYRot());
                bulletLateralOffset = offset.x * Math.cos(yaw) + offset.z * Math.sin(yaw);
                BULLETS.incrementAndGet();
            }
        } catch (Throwable error) { fail(error); }
    }

    private static void wall(Minecraft mc, boolean present) {
        mc.getSingleplayerServer().submit(() -> {
            for (int y = -60; y <= -57; y++) for (int z = -1; z <= 1; z++)
                player(mc).serverLevel().setBlockAndUpdate(new BlockPos(1, y, z),
                        (present ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }).join();
    }

    private static void key(boolean left, boolean right) {
        TacticalActionsKeyMappings.LEAN_LEFT.setDown(left);
        TacticalActionsKeyMappings.LEAN_RIGHT.setDown(right);
        if (left) KeyMapping.click(TacticalActionsKeyMappings.LEAN_LEFT.getKey());
        if (right) KeyMapping.click(TacticalActionsKeyMappings.LEAN_RIGHT.getKey());
    }

    private static void freezeView(Minecraft mc) {
        mc.player.setYRot(0); mc.player.yRotO = 0;
        mc.player.setXRot(0); mc.player.xRotO = 0;
        mc.player.setYHeadRot(0); mc.player.yHeadRotO = 0;
        mc.player.yBodyRot = 0; mc.player.yBodyRotO = 0;
    }

    private static void releaseInput(Minecraft mc) {
        key(false, false);
        mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
        mc.options.keyJump.setDown(false); mc.options.keyShift.setDown(false); mc.options.keySprint.setDown(false);
        mc.options.keyUp.setDown(false); mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false);
    }

    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.sgr792.gwo." + name); }
    private static Class<?> support() throws ClassNotFoundException { return Class.forName("dev.herrastudio.tacticalactions.GwoPeekSupport"); }
    private static boolean isGun(ItemStack stack) throws Exception { return (boolean) support().getMethod("isGun", ItemStack.class).invoke(null, stack); }
    private static boolean isGunUnchecked(ItemStack stack) { try { return isGun(stack); } catch (Exception e) { throw new RuntimeException(e); } }
    private static long diagnostics(String method) throws Exception { return ((Number) support().getMethod(method).invoke(null)).longValue(); }
    private static ServerPlayer player(Minecraft mc) { return mc.getSingleplayerServer().getPlayerList().getPlayer(playerId); }
    private static <T> T server(Minecraft mc, java.util.function.Function<ServerPlayer, T> action) {
        return mc.getSingleplayerServer().submit(() -> action.apply(player(mc))).join();
    }
    private static void change(Phase next) { phase = next; phaseTick = 0; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    private static void finish(Minecraft mc) throws Exception {
        releaseInput(mc);
        check(frameCount > 100, "live render frames captured");
        String result = "PEEK_SMOKE_PASS assertions=" + checks + " frames=" + frameCount
                + " client_server_position_aabb_anchored=true root_legs_unchanged=true"
                + " directions_dual_release_menu_wall=true server_state=true gwo=" + gwo
                + " native_renderer=" + rendererProbed + " native_thirdperson_renderer=" + thirdRendererProbed
                + " native_authored_thirdperson_renderer=" + authoredRendererProbed
                + " native_server_bullets=" + BULLETS.get()
                + " protected_graphics_verified=false";
        Files.writeString(output.resolve("frames.csv"), FRAMES);
        Files.writeString(output.resolve("result.txt"), result + "\n");
        LoggerFactory.getLogger("PeekSmoke").info(result);
        stopped = true;
        mc.stop();
    }

    private static void fail(Throwable error) {
        if (stopped) return;
        stopped = true;
        var mc = Minecraft.getInstance();
        releaseInput(mc);
        LoggerFactory.getLogger("PeekSmoke").error("PEEK_SMOKE_FAIL phase=" + phase + " tick=" + phaseTick, error);
        try {
            if (output != null) {
                Files.writeString(output.resolve("frames.csv"), FRAMES);
                Files.writeString(output.resolve("result.txt"), "PEEK_SMOKE_FAIL phase=" + phase + "\n" + error + "\n");
            }
        } catch (Exception ignored) { }
        mc.stop();
    }

    private record Snapshot(Vec3 position, AABB box) {
        private static Snapshot of(net.minecraft.world.entity.Entity p) { return new Snapshot(p.position(), p.getBoundingBox()); }
        private boolean matches(Snapshot other) {
            return position.distanceTo(other.position) < EPS && Math.abs(box.minX - other.box.minX) < EPS
                    && Math.abs(box.minY - other.box.minY) < EPS && Math.abs(box.minZ - other.box.minZ) < EPS
                    && Math.abs(box.maxX - other.box.maxX) < EPS && Math.abs(box.maxY - other.box.maxY) < EPS
                    && Math.abs(box.maxZ - other.box.maxZ) < EPS;
        }
    }

    private record PartState(float x, float y, float z, float rx, float ry, float rz) {
        private static PartState of(ModelPart part) { return new PartState(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot); }
        private boolean matches(ModelPart part) {
            return Math.abs(x - part.x) < EPS && Math.abs(y - part.y) < EPS && Math.abs(z - part.z) < EPS
                    && Math.abs(rx - part.xRot) < EPS && Math.abs(ry - part.yRot) < EPS && Math.abs(rz - part.zRot) < EPS;
        }
    }
}
