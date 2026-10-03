package dev.herrastudio.tacticalactions;

import com.mojang.blaze3d.platform.InputConstants;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.easing.EasingType;
import com.zigythebird.playeranimcore.enums.FadeType;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Local input, skeletal transitions and first-person camera presentation. */
@EventBusSubscriber(modid = dev.herrastudio.raidcore.RaidCore.MOD_ID, value = Dist.CLIENT)
public final class TacticalActionsClient {
    public static final ResourceLocation LAYER = id("tactical_actions");
    private static final ResourceLocation PRONE = id("prone");
    private static final int RETURN_TICKS = 6;
    private static final AutomaticPeek AUTOMATIC_PEEK = new AutomaticPeek();
    private static final Map<UUID, PeekNetwork.Snapshot> REMOTE_PEEKS = new HashMap<>();
    private static int lastSentDirection = Integer.MIN_VALUE;
    private static int heartbeat;
    private static int toggledDirection;
    private static boolean wasLeft, wasRight;
    private static AbstractClientPlayer trackedPlayer;
    private static ClientLevel trackedLevel;
    private static boolean prone;
    private static boolean ownsPronePose;
    private static TacticalActionState active = TacticalActionState.STANDING;
    private static int returnTicks;
    private static int proneVisualCooldown;

    private TacticalActionsClient() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TacticalActions.MOD_ID, path);
    }

    @SubscribeEvent
    public static void onBeforeClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!canAct(mc)) return;
        // Also covers synthetic key input, which does not emit InputEvent.Key.
        for (KeyMapping action : new KeyMapping[]{TacticalActionsKeyMappings.LEAN_LEFT,
                TacticalActionsKeyMappings.LEAN_RIGHT, TacticalActionsKeyMappings.PRONE}) {
            if (action.isDown()) consumeVanillaConflict(action.getKey());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != trackedPlayer || mc.level != trackedLevel) {
            reset();
            trackedPlayer = mc.player;
            trackedLevel = mc.level;
            proneVisualCooldown = 0;
            REMOTE_PEEKS.clear();
            lastSentDirection = Integer.MIN_VALUE;
        }
        applyRemotePeeks(mc);
        if (!canAct(mc)) {
            reset();
            discardActionClicks();
            return;
        }
        if (proneVisualCooldown > 0) --proneVisualCooldown;
        while (TacticalActionsKeyMappings.PRONE.consumeClick()) prone = !prone;
        drain(TacticalActionsKeyMappings.LEAN_LEFT);
        drain(TacticalActionsKeyMappings.LEAN_RIGHT);
        boolean left = TacticalActionsKeyMappings.LEAN_LEFT.isDown();
        boolean right = TacticalActionsKeyMappings.LEAN_RIGHT.isDown();
        int direction;
        if (PeekClientConfig.TOGGLE_INPUT.get()) {
            if (left && right) toggledDirection = 0;
            else if (left && !wasLeft) toggledDirection = toggledDirection == 1 ? 0 : 1;
            else if (right && !wasRight) toggledDirection = toggledDirection == -1 ? 0 : -1;
            direction = toggledDirection;
        } else direction = left == right ? 0 : left ? 1 : -1;
        wasLeft = left;
        wasRight = right;
        if (prone) { direction = 0; toggledDirection = 0; AUTOMATIC_PEEK.reset(); }
        else if (left || right || direction != 0) AUTOMATIC_PEEK.reset();
        else direction = AUTOMATIC_PEEK.tick(mc.player);
        TacticalActionState requested = prone ? TacticalActionState.PRONE
                : direction > 0 ? TacticalActionState.LEAN_LEFT
                : direction < 0 ? TacticalActionState.LEAN_RIGHT : TacticalActionState.STANDING;
        PeekStates.tick(mc.player, direction);
        sendPeekDirection(direction);
        applyPronePose(mc.player, requested == TacticalActionState.PRONE);
        PlayerAnimationController controller = controller(mc.player);
        if (controller == null) return;
        if (returnTicks > 0 && --returnTicks == 0) stop(controller);
        if (active == TacticalActionState.PRONE && requested != TacticalActionState.PRONE) {
            proneVisualCooldown = RETURN_TICKS + 2;
            // Held animations have no natural end; fade to vanilla, then release.
            controller.removeAllModifiers();
            controller.addModifierLast(new ReturnToVanilla(RETURN_TICKS));
            returnTicks = RETURN_TICKS;
        } else if (requested == TacticalActionState.PRONE && active != TacticalActionState.PRONE) {
            controller.removeAllModifiers();
            controller.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(5, EasingType.EASE_IN_OUT_SINE), PRONE);
            returnTicks = 0;
        }
        active = requested;
    }

    public static boolean hasLean(AbstractClientPlayer player) {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        return Math.abs(PeekStates.leanAt(player, partialTick)) * PeekPoseMath.ANGLE_DEGREES > 0.05F
                || Math.abs(PeekStates.offsetAt(player, partialTick)) * PeekGeometry.DISTANCE > 0.005F;
    }
    public static float leanAt(float partialTick) {
        return leanAt(trackedPlayer, partialTick);
    }
    public static float leanAt(Player player, float partialTick) {
        return PeekStates.leanAt(player, partialTick);
    }
    public static void acceptRemotePeek(PeekNetwork.Snapshot snapshot) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && !mc.player.getUUID().equals(snapshot.player())
                && Float.isFinite(snapshot.previous()) && Float.isFinite(snapshot.current())
                && Float.isFinite(snapshot.previousOffset()) && Float.isFinite(snapshot.currentOffset())) REMOTE_PEEKS.put(snapshot.player(), snapshot);
    }
    private static void applyRemotePeeks(Minecraft mc) {
        if (mc.level == null) return;
        for (Player player : mc.level.players()) {
            if (player != mc.player) PeekStates.settleRemote(player);
        }
        REMOTE_PEEKS.entrySet().removeIf(entry -> {
            Player player = mc.level.getPlayerByUUID(entry.getKey());
            if (player == null) return false;
            PeekNetwork.Snapshot snapshot = entry.getValue();
            PeekStates.accept(player, snapshot.previous(), snapshot.current(), snapshot.previousOffset(), snapshot.currentOffset());
            return true;
        });
    }
    private static void sendPeekDirection(int direction) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null && mc.getConnection().hasChannel(PeekNetwork.Request.TYPE)
                && (direction != lastSentDirection || ++heartbeat >= 20)) {
            PacketDistributor.sendToServer(new PeekNetwork.Request(direction));
            lastSentDirection = direction;
            heartbeat = 0;
        }
    }

    private static boolean canAct(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.screen == null
                && mc.player.isAlive() && !mc.player.isSpectator()
                && !mc.player.isPassenger() && !mc.player.isSleeping()
                && !mc.player.isFallFlying() && !mc.player.getAbilities().flying
                && !mc.player.isInWater() && !mc.player.isInLava()
                && !mc.player.isSwimming();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS || event.getAction() == GLFW.GLFW_REPEAT) {
            consumeVanillaConflict(InputConstants.getKey(event.getKey(), event.getScanCode()));
        }
    }

    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_PRESS) {
            consumeVanillaConflict(InputConstants.Type.MOUSE.getOrCreate(event.getButton()));
        }
    }

    private static void consumeVanillaConflict(InputConstants.Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (!canAct(mc)) return;
        if (TacticalActionsKeyMappings.LEAN_LEFT.isActiveAndMatches(key)
                || TacticalActionsKeyMappings.LEAN_RIGHT.isActiveAndMatches(key)
                || TacticalActionsKeyMappings.PRONE.isActiveAndMatches(key)) {
            if (mc.options.keyDrop.isActiveAndMatches(key)) drain(mc.options.keyDrop);
            if (mc.options.keyInventory.isActiveAndMatches(key)) drain(mc.options.keyInventory);
        }
    }

    @SubscribeEvent
    public static void onScreenOpened(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null) {
            reset();
            discardActionClicks();
        }
    }

    /** After vanilla Camera.setup, which otherwise overwrites camera position. */
    public static CameraTransform cameraTransform(Camera camera, BlockGetter level, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!canAct(mc) || camera.getEntity() != mc.player) return CameraTransform.NONE;
        float lean = leanAt(partialTick);
        if (lean == 0 && PeekStates.offsetAt(mc.player, partialTick) == 0) return CameraTransform.NONE;
        if (camera.isDetached() || mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            return new CameraTransform(Vec3.ZERO, -(float) Math.toRadians(PeekPoseMath.ANGLE_DEGREES * lean) * 0.7F);
        }
        Vec3 offset = PeekGeometry.offset(mc.player, PeekStates.offsetAt(mc.player, partialTick));
        float clearance = PeekGeometry.clearance(level, mc.player, camera.getPosition(), offset);
        return new CameraTransform(offset.scale(clearance), -(float) Math.toRadians(PeekPoseMath.ANGLE_DEGREES * lean) * 55.0F);
    }

    private static void applyPronePose(AbstractClientPlayer player, boolean requested) {
        if (requested) {
            if (!ownsPronePose && player.getForcedPose() == null) {
                ownsPronePose = true;
                player.setForcedPose(Pose.SWIMMING);
            }
            if (ownsPronePose) player.setPose(Pose.SWIMMING);
        } else if (ownsPronePose) {
            // Vanilla chooses the pose from headroom; never force swimming to standing.
            if (player.getForcedPose() == Pose.SWIMMING) player.setForcedPose(null);
            ownsPronePose = false;
            proneVisualCooldown = RETURN_TICKS + 2;
        }
    }

    /** PAL supplies prone rotation; vanilla must not rotate the model twice. */
    public static boolean suppressVanillaSwim(LivingEntity entity) {
        return entity == trackedPlayer && (ownsPronePose || proneVisualCooldown > 0)
                && entity.isAlive() && !entity.isInWater() && !entity.isInLava()
                && !entity.isSwimming();
    }

    private static void reset() {
        if (trackedPlayer != null) {
            if (lastSentDirection != 0) sendPeekDirection(0);
            PeekStates.reset(trackedPlayer);
            applyPronePose(trackedPlayer, false);
            PlayerAnimationController controller = controller(trackedPlayer);
            if (controller != null) stop(controller);
        }
        prone = false;
        active = TacticalActionState.STANDING;
        returnTicks = 0;
        toggledDirection = 0;
        wasLeft = wasRight = false;
        AUTOMATIC_PEEK.reset();
    }

    private static void stop(PlayerAnimationController controller) {
        controller.stopTriggeredAnimation();
        controller.stop();
        controller.removeAllModifiers();
    }

    private static void discardActionClicks() {
        drain(TacticalActionsKeyMappings.LEAN_LEFT);
        drain(TacticalActionsKeyMappings.LEAN_RIGHT);
        drain(TacticalActionsKeyMappings.PRONE);
    }

    private static void drain(KeyMapping mapping) {
        while (mapping.consumeClick()) { /* Keep saved key bindings untouched. */ }
    }

    private static PlayerAnimationController controller(AbstractClientPlayer player) {
        var layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        return layer instanceof PlayerAnimationController controller ? controller : null;
    }

    public record CameraTransform(Vec3 offset, float roll) {
        public static final CameraTransform NONE = new CameraTransform(Vec3.ZERO, 0.0F);
    }

    private static final class ReturnToVanilla extends AbstractFadeModifier {
        private ReturnToVanilla(int ticks) { super(ticks); }
        @Override protected float getEndTime(String bone) { return length; }
        @Override protected FadeType getFadeType() { return FadeType.FADE_OUT; }
        @Override protected float getAlpha(String bone, float progress) {
            float clamped = Mth.clamp(progress, 0.0F, 1.0F);
            return clamped * clamped * (3.0F - 2.0F * clamped);
        }
    }
}
