package dev.herrastudio.tacticalactions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the live GD656-style pose math rather than a disconnected legacy JSON clip. */
class PeekPoseMathTest {
    private static final float EPSILON = .00001f;

    @Test
    void neutralAndLowerBodyRemainUnchangedAtEveryLeanAmount() {
        for (float lean : new float[]{-1f, -.5f, -.01f, 0f, .01f, .5f, 1f}) {
            for (String bone : new String[]{"body", "left_leg", "right_leg", "left_item", "right_item", "unknown"}) {
                assertZero(PeekPoseMath.delta(bone, lean), bone + " at " + lean);
            }
        }
        for (String bone : new String[]{"torso", "head", "left_arm", "right_arm", "cape"}) {
            assertZero(PeekPoseMath.delta(bone, 0f), "neutral " + bone);
        }
    }

    @Test
    void bothDirectionsMoveOnlyUpperBodyWithFiniteBoundedTransforms() {
        for (float lean : new float[]{-1f, -.75f, -.25f, .25f, .75f, 1f}) {
            for (String bone : new String[]{"torso", "head", "left_arm", "right_arm"}) {
                var d = PeekPoseMath.delta(bone, lean);
                assertTrue(Float.isFinite(d.dx()) && Float.isFinite(d.dy()) && Float.isFinite(d.dz()));
                assertTrue(Float.isFinite(d.rx()) && Float.isFinite(d.ry()) && Float.isFinite(d.rz()));
                assertTrue(Math.abs(d.dx()) < 12 && Math.abs(d.dy()) < 5 && Math.abs(d.dz()) < 5,
                        "the pose must bend the upper body rather than move a whole player");
                assertTrue(Math.abs(d.rx()) < 1 && Math.abs(d.ry()) < 1 && Math.abs(d.rz()) < 1);
                assertTrue(Math.abs(d.dx()) > .001f && Math.abs(d.rz()) > .001f, bone);
            }
        }
    }

    @Test
    void headAndTorsoLeanAreSymmetricAndProgressive() {
        for (String bone : new String[]{"torso", "head"}) {
            var left = PeekPoseMath.delta(bone, 1f);
            var right = PeekPoseMath.delta(bone, -1f);
            assertEquals(left.dx(), -right.dx(), EPSILON, bone + " lateral mirror");
            assertEquals(left.rz(), -right.rz(), EPSILON, bone + " roll mirror");
            assertEquals(left.dy(), right.dy(), .001f, bone + " equal vertical lowering within original sine-table quantization");
            float previousDistance = 0;
            for (int step = 0; step <= 20; step++) {
                var d = PeekPoseMath.delta(bone, step / 20f);
                assertTrue(Math.abs(d.dx()) >= previousDistance - EPSILON, bone + " progressive lean");
                previousDistance = Math.abs(d.dx());
            }
        }
        assertTrue(Math.abs(PeekPoseMath.delta("head", 1).dx()) > .5f,
                "the head must actually peek around the cover");
    }

    @Test
    void malformedOrOutOfRangeStateCannotCorruptTheModel() {
        assertZero(PeekPoseMath.delta(null, 1), "absent bone");
        for (float lean : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertZero(PeekPoseMath.delta("head", lean), "nonfinite state");
        assertEquals(PeekPoseMath.delta("head", 1), PeekPoseMath.delta("head", 30));
        assertEquals(PeekPoseMath.delta("head", -1), PeekPoseMath.delta("head", -30));
    }

    @Test
    void everyUpperBodyComponentMatchesOriginalGd656CoefficientsAndTwoTrackDirection() {
        for (String bone : new String[]{"torso", "head", "left_arm", "right_arm"}) {
            for (float[] state : new float[][]{{1, 1}, {-1, -1}, {.5f, .7f}, {-.5f, -.7f}, {.002f, 0}, {0, .1f}}) {
                var expected = sourcePose(bone, state[0], state[1]);
                var actual = PeekPoseMath.delta(bone, state[0], state[1]);
                assertEquals(expected.dx(), actual.dx(), EPSILON, bone + " source x");
                assertEquals(expected.dy(), actual.dy(), EPSILON, bone + " source y");
                assertEquals(expected.dz(), actual.dz(), EPSILON, bone + " source z");
                assertEquals(expected.rx(), actual.rx(), EPSILON, bone + " source pitch");
                assertEquals(expected.ry(), actual.ry(), EPSILON, bone + " source yaw");
                assertEquals(expected.rz(), actual.rz(), EPSILON, bone + " source roll");
            }
        }
    }

    /** Original BetterPeekPoseAnimation arithmetic, with GD656's default 15-degree angle. */
    private static PeekPoseMath.Delta sourcePose(String bone, float angleNormalized, float offsetNormalized) {
        float angle = (float) Math.toRadians(15f * angleNormalized);
        float direction = offsetNormalized != 0 ? -Math.signum(offsetNormalized) : Math.signum(angleNormalized);
        float intensity = Math.max(Math.abs(angleNormalized), Math.abs(offsetNormalized));
        float sin = sourceSin(angle), oneMinusCos = 1f - sourceCos(angle);
        float shoulderX = sin * 9.1f * 1.1f + sin * 1.5f;
        float shoulderY = -oneMinusCos * 9.1f * 1.1f - .03f;
        return switch (bone) {
            case "torso" -> new PeekPoseMath.Delta((sin * 6.4f * 1.08f + sin * 10f) * intensity,
                    (-oneMinusCos * 6.4f * 1.08f + 1f) * intensity, 0,
                    -.06f * intensity, .05f * direction * intensity, angle * 1.08f);
            case "head" -> new PeekPoseMath.Delta((sin * 12.3f * 1.11f + sin * .52f) * intensity,
                    (-oneMinusCos * 12.3f * 1.11f + 1f - .06f) * intensity, 0,
                    -.02f * intensity, .09f * direction * intensity, angle * 1.1f);
            case "right_arm" -> new PeekPoseMath.Delta((shoulderX - .18f) * intensity,
                    (shoulderY - .04f) * intensity, 0,
                    -.1f * intensity, .06f * direction * intensity, angle * .96f - Math.abs(angle) * .08f);
            case "left_arm" -> new PeekPoseMath.Delta((shoulderX + .12f) * intensity,
                    (shoulderY + .02f) * intensity, 0,
                    .04f * intensity, .02f * direction * intensity, angle * .96f + Math.abs(angle) * .03f);
            default -> PeekPoseMath.Delta.ZERO;
        };
    }

    private static float sourceSin(float radians) {
        int index = (int) (radians * 10430.378f) & 65535;
        return (float) Math.sin(index * Math.PI * 2 / 65536);
    }

    private static float sourceCos(float radians) {
        int index = (int) (radians * 10430.378f + 16384f) & 65535;
        return (float) Math.sin(index * Math.PI * 2 / 65536);
    }

    private static void assertZero(PeekPoseMath.Delta d, String label) {
        assertEquals(0, d.dx(), EPSILON, label + " x");
        assertEquals(0, d.dy(), EPSILON, label + " y");
        assertEquals(0, d.dz(), EPSILON, label + " z");
        assertEquals(0, d.rx(), EPSILON, label + " pitch");
        assertEquals(0, d.ry(), EPSILON, label + " yaw");
        assertEquals(0, d.rz(), EPSILON, label + " roll");
    }
}
