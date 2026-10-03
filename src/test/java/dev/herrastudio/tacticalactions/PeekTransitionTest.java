package dev.herrastudio.tacticalactions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Golden traces of GD656Peek 0.1.1's approachByDuration at its default 0.2 seconds. */
class PeekTransitionTest {
    private static final float EPS = .000001f;

    @Test
    void entryUsesTheOriginalFourSamplesOfEaseOutCubic() {
        for (float sign : new float[]{-1, 1}) {
            var transition = new PeekTransition();
            for (float fraction : new float[]{.578125f, .875f, .984375f, 1f}) {
                float previous = transition.current();
                transition.tick(sign);
                assertEquals(sign * fraction, transition.current(), EPS);
                assertEquals(sign * fraction, transition.currentOffset(), EPS);
                assertEquals((previous + transition.current()) / 2, transition.get(.5f), EPS);
            }
        }
    }

    @Test
    void releaseUsesOriginalMagnitudeApproachRatherThanAFourTickFade() {
        var transition = heldLeft();
        for (float expected : new float[]{.75f, .5625f, .421875f, .31640625f, .2373046875f}) {
            transition.tick(0);
            assertEquals(expected, transition.current(), EPS);
            assertEquals(expected, transition.currentOffset(), EPS);
        }
        assertTrue(transition.current() > .2f, "the source release retains its gradual tail");
        for (int tick = 0; tick < 80; tick++) transition.tick(0);
        assertEquals(0, transition.current(), EPS);
        assertEquals(0, transition.currentOffset(), EPS);
    }

    @Test
    void sideSwitchRetainsTheSourceLinearCrossingThenCubicEntry() {
        var transition = heldLeft();
        for (float expected : new float[]{.75f, .5f, .25f, 0f, -.578125f, -.875f, -.984375f, -1f}) {
            transition.tick(-1);
            assertEquals(expected, transition.current(), EPS);
            assertEquals(expected, transition.currentOffset(), EPS);
        }
    }

    @Test
    void interruptedEntryAndChangingCoverClearanceMatchTheSourceAlgorithm() {
        var transition = new PeekTransition();
        float rawAngle = 0, rawOffset = 0;
        for (float target : new float[]{1, 1, .72f, .72f, 0, 0, -.4f, -.4f, -1, -1, .5f, .5f, 0, 0}) {
            float previousAngle = rawAngle, previousOffset = rawOffset;
            rawAngle = sourceApproach(rawAngle, target * 15);
            rawOffset = sourceApproach(rawOffset, target * .3f);
            transition.tick(target);
            assertEquals(rawAngle / 15f, transition.current(), EPS, "angle target=" + target);
            assertEquals(rawOffset / .3f, transition.currentOffset(), EPS, "offset target=" + target);
            assertEquals(previousAngle / 15f, transition.get(0), EPS);
            assertEquals(rawAngle / 15f, transition.get(1), EPS);
            assertEquals((previousAngle + (rawAngle - previousAngle) * .3f) / 15f, transition.get(.3f), EPS);
            assertEquals((previousOffset + (rawOffset - previousOffset) * .3f) / .3f, transition.offset(.3f), EPS);
        }
    }

    @Test
    void releaseRetainsTheOriginalDifferentAngleAndOffsetTailThresholds() {
        var transition = heldLeft();
        float rawAngle = 15, rawOffset = .3f;
        int angleZeroAt = 0, offsetZeroAt = 0;
        for (int tick = 1; tick <= 80; tick++) {
            rawAngle = sourceApproach(rawAngle, 0);
            rawOffset = sourceApproach(rawOffset, 0);
            transition.tick(0);
            assertEquals(rawAngle / 15f, transition.current(), EPS, "angle release tick " + tick);
            assertEquals(rawOffset / .3f, transition.currentOffset(), EPS, "offset release tick " + tick);
            if (angleZeroAt == 0 && transition.current() == 0) angleZeroAt = tick;
            if (offsetZeroAt == 0 && transition.currentOffset() == 0) offsetZeroAt = tick;
        }
        assertTrue(offsetZeroAt > 4 && offsetZeroAt < angleZeroAt,
                "the source threshold is in raw model units, so the two tracks finish separately");
    }

    @Test
    void resetClearsInterpolationHistoryAndInvalidStateStaysFinite() {
        var transition = heldLeft();
        transition.reset();
        assertEquals(0, transition.current(), EPS);
        assertEquals(0, transition.currentOffset(), EPS);
        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
            assertEquals(0, transition.get(partial), EPS);
            assertEquals(0, transition.offset(partial), EPS);
        }
        transition.tick(100);
        assertTrue(transition.current() >= 0 && transition.current() <= 1);
        transition.tick(Float.NaN);
        assertTrue(Float.isFinite(transition.current()));
        assertTrue(Float.isFinite(transition.get(Float.NaN)));
    }

    private static PeekTransition heldLeft() {
        var transition = new PeekTransition();
        for (int tick = 0; tick < 4; tick++) transition.tick(1);
        return transition;
    }

    /** Independent reference transcribed from the attached jar without retiming. */
    private static float sourceApproach(float current, float target) {
        boolean peekingOut = Math.abs(target) > .0005f
                && Math.abs(target) > Math.abs(current) + .0005f
                && (Math.abs(current) <= .0005f || Math.signum(current) == Math.signum(target));
        if (peekingOut) {
            float magnitude = Math.abs(target);
            float fraction = Math.max(0, Math.min(1, Math.abs(current) / magnitude));
            float t = 1f - (float) Math.cbrt(1.0 - fraction);
            float nextT = Math.min(t + .25f, 1);
            float next = Math.copySign(magnitude * (1f - (float) Math.pow(1f - nextT, 3)), target);
            return Math.abs(next - target) < .0005f ? target : next;
        }
        float reference = Math.max(Math.abs(current), Math.abs(target));
        if (reference < .0005f) return target;
        float delta = reference / 4f;
        return Math.abs(target - current) <= delta ? target : current + Math.copySign(delta, target - current);
    }
}
