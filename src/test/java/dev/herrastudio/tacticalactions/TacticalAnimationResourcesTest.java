package dev.herrastudio.tacticalactions;

import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.animation.AnimationController;
import com.zigythebird.playeranimcore.animation.AnimationData;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import com.zigythebird.playeranimcore.enums.PlayState;
import com.zigythebird.playeranimcore.loading.UniversalAnimLoader;
import com.zigythebird.playeranimcore.math.Vec3f;
import com.zigythebird.playeranimcore.molang.MolangLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises PAL's real JSON loader, easing and parent-bone evaluator without a game window. */
class TacticalAnimationResourcesTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void proneHasOneRootRotationConnectedJointsAndAHorizonalBody() throws IOException {
        Animation animation = load("tactical_prone.json", "prone");
        for (float seconds : new float[]{0, 0.08F, 0.21F, 0.35F, 0.419F, 0.8F}) {
            CoreController controller = sample(animation, seconds);
            Matrix4f root = rootMatrix(controller.part("body"));
            Vector3f waist = root.mul(partMatrix("torso", controller.part("torso")), new Matrix4f())
                    .transformPosition(new Vector3f(0, 12, 0));
            Vector3f leftHip = root.mul(partMatrix("left_leg", controller.part("left_leg")), new Matrix4f())
                    .transformPosition(new Vector3f());
            Vector3f rightHip = root.mul(partMatrix("right_leg", controller.part("right_leg")), new Matrix4f())
                    .transformPosition(new Vector3f());
            assertVector(waist, leftHip.add(rightHip).mul(0.5F), "prone hip joint at " + seconds);
            for (String part : new String[]{"torso", "left_leg", "right_leg"}) {
                assertUnchanged(controller.part(part), "only the root rotates the prone " + part);
            }
            Vector3f neck = root.mul(partMatrix("head", controller.part("head")), new Matrix4f())
                    .transformPosition(new Vector3f());
            Vector3f chestTop = root.mul(partMatrix("torso", controller.part("torso")), new Matrix4f())
                    .transformPosition(new Vector3f());
            assertVector(chestTop, neck, "prone neck joint at " + seconds);
        }

        CoreController held = sample(animation, 0.8F);
        assertEquals(Math.PI / 2, held.part("body").rotX, EPSILON);
        Matrix4f root = rootMatrix(held.part("body"));
        Matrix4f torso = root.mul(partMatrix("torso", held.part("torso")), new Matrix4f());
        Vector3f shoulder = torso.transformPosition(new Vector3f());
        Vector3f waist = torso.transformPosition(new Vector3f(0, 12, 0));
        Vector3f foot = root.mul(partMatrix("left_leg", held.part("left_leg")), new Matrix4f())
                .transformPosition(new Vector3f(0, 12, 0));
        assertEquals(shoulder.y, waist.y, EPSILON, "chest must be horizontal");
        assertEquals(waist.y, foot.y, EPSILON, "legs must lie alongside chest, not point skyward");
        assertEquals(0.125F, waist.y, EPSILON, "root must lower body to the floor");
        for (float x : new float[]{-4, 4}) {
            for (float y : new float[]{0, 12}) {
                for (float z : new float[]{-2, 2}) {
                    float height = torso.transformPosition(new Vector3f(x, y, z)).y;
                    assertTrue(height >= -EPSILON && height <= 0.25F,
                            "torso must be above the floor and under 0.25 blocks high: " + height);
                }
            }
        }
        Matrix4f head = root.mul(partMatrix("head", held.part("head")), new Matrix4f());
        Vector3f gaze = head.transformDirection(new Vector3f(0, 0, -1)).normalize();
        assertVector(new Vector3f(0, 0, -1), gaze, "head must counter-rotate and look forward");
    }

    @Test
    void proneEntryUsesAMonotonicNonlinearCurve() throws IOException {
        Animation animation = load("tactical_prone.json", "prone");
        float previous = -1;
        for (int frame = 0; frame <= 40; frame++) {
            float rotation = sample(animation, frame * 0.42F / 40).part("body").rotX;
            assertTrue(rotation >= previous - EPSILON, "prone must not overshoot or reverse");
            assertTrue(rotation >= -EPSILON && rotation <= Math.PI / 2 + EPSILON);
            previous = rotation;
        }
        assertTrue(sample(animation, 0.105F).part("body").rotX < Math.toRadians(20),
                "quarter-time entry should ease in, rather than move linearly to 22.5 degrees");
    }

    private static Animation load(String file, String name) throws IOException {
        try (InputStream input = TacticalAnimationResourcesTest.class.getResourceAsStream(
                "/assets/tacticalactions/player_animations/" + file)) {
            assertNotNull(input, file);
            Map<String, Animation> animations = UniversalAnimLoader.loadAnimations(input);
            assertTrue(animations.containsKey(name), "PAL must decode " + name);
            return animations.get(name);
        }
    }

    private static CoreController sample(Animation animation, float seconds) {
        CoreController controller = new CoreController();
        controller.triggerAnimation(animation);
        controller.setupAnim(new AnimationData(0, seconds * 20));
        return controller;
    }

    /** PAL RenderUtil translates positive bone Y upward into Minecraft's downward model Y. */
    private static Matrix4f partMatrix(String name, PlayerAnimBone bone) {
        Vec3f pivot = CoreController.POSITIONS.get(name);
        return new Matrix4f().translate(-pivot.x() + bone.positionX, 24 - pivot.y() - bone.positionY,
                        pivot.z() + bone.positionZ)
                .rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
    }

    /** PAL PlayerRendererMixin root transform, then vanilla LivingEntityRenderer/PlayerRenderer. */
    private static Matrix4f rootMatrix(PlayerAnimBone body) {
        return new Matrix4f().scale(body.scaleX, body.scaleY, body.scaleZ)
                .translate(-body.positionX / 16, body.positionY / 16 + 0.75F, body.positionZ / 16)
                .rotateZYX(body.rotZ, -body.rotY, -body.rotX)
                .translate(0, -0.75F, 0)
                .scale(-1, -1, 1)
                .scale(0.9375F)
                .translate(0, -1.501F, 0)
                .scale(1.0F / 16);
    }

    private static void assertUnchanged(PlayerAnimBone bone, String message) {
        assertEquals(0, bone.positionX, EPSILON, message);
        assertEquals(0, bone.positionY, EPSILON, message);
        assertEquals(0, bone.positionZ, EPSILON, message);
        assertEquals(0, bone.rotX, EPSILON, message);
        assertEquals(0, bone.rotY, EPSILON, message);
        assertEquals(0, bone.rotZ, EPSILON, message);
    }

    private static void assertVector(Vector3f expected, Vector3f actual, String message) {
        assertEquals(expected.x, actual.x, EPSILON, message + " x");
        assertEquals(expected.y, actual.y, EPSILON, message + " y");
        assertEquals(expected.z, actual.z, EPSILON, message + " z");
    }

    /** The published PAL PlayerAnimationController BONE_POSITIONS, in model pixels. */
    private static final class CoreController extends AnimationController {
        private static final Map<String, Vec3f> POSITIONS = Map.of(
                "body", new Vec3f(0, 12, 0), "torso", new Vec3f(0, 24, 0),
                "head", new Vec3f(0, 24, 0), "right_arm", new Vec3f(5, 22, 0),
                "left_arm", new Vec3f(-5, 22, 0), "right_leg", new Vec3f(2, 12, 0),
                "left_leg", new Vec3f(-2, 12, 0));

        private CoreController() {
            super((controller, state, setter) -> PlayState.STOP, MolangLoader::createNewEngine);
        }

        @Override public void registerBones() {
            POSITIONS.keySet().forEach(this::registerPlayerAnimBone);
        }

        @Override public Vec3f getBonePosition(String name) {
            return POSITIONS.containsKey(name) ? POSITIONS.get(name) : pivotBones.get(name).getPivot();
        }

        PlayerAnimBone part(String name) {
            return get3DTransform(new PlayerAnimBone(name));
        }
    }
}
