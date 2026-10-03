package dev.draginventory;

import dev.draginventory.client.WeaponHudMotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeaponHudMotionTest {
    private static WeaponHudMotion.Pose idle(WeaponHudMotion motion) {
        return motion.step(1f / 60, 0, 0, false, 0, true, 0, 0);
    }

    @Test void stationaryHudHasNoRandomJitter() {
        var motion = new WeaponHudMotion();
        for (int i = 0; i < 600; i++) assertEquals(WeaponHudMotion.REST, idle(motion));
    }

    @Test void cameraFollowsBothAxesAndSettles() {
        var motion = new WeaponHudMotion();
        idle(motion);
        var rightDown = motion.step(1f / 60, 2, 2, false, 0, true, 0, 0);
        assertTrue(rightDown.x() > 0 && rightDown.y() > 0);
        WeaponHudMotion.Pose leftUp = null;
        for (int i = 1; i <= 12; i++) leftUp = motion.step(1f / 60, 2 - i, 2 - i, false, 0, true, 0, 0);
        assertTrue(leftUp.x() < 0 && leftUp.y() < 0);
        WeaponHudMotion.Pose last = null;
        for (int i = 0; i < 120; i++) last = motion.step(1f / 60, -10, -10, false, 0, true, 0, 0);
        assertEquals(WeaponHudMotion.REST, last);
    }

    @Test void yawBoundaryUsesSmallAngleAcrossNorth() {
        var wrapped = new WeaponHudMotion();
        var plain = new WeaponHudMotion();
        wrapped.step(0.02f, 179, 0, false, 0, true, 0, 0);
        plain.step(0.02f, -1, 0, false, 0, true, 0, 0);
        assertEquals(plain.step(0.02f, 1, 0, false, 0, true, 0, 0),
                wrapped.step(0.02f, -179, 0, false, 0, true, 0, 0));
    }

    @Test void sprintNeedsRealMovementAndGroundContact() {
        var motion = new WeaponHudMotion();
        for (int i = 0; i < 120; i++)
            assertEquals(WeaponHudMotion.REST, motion.step(1f / 60, 0, 0, true, 0, true, 0, 0));
        boolean positive = false, negative = false;
        for (int i = 0; i < 120; i++) {
            var pose = motion.step(1f / 60, 0, 0, true, 5.6f, true, 0, 0);
            positive |= pose.y() > 0.2;
            negative |= pose.y() < -0.2;
        }
        assertTrue(positive && negative);
        for (int i = 0; i < 180; i++) idle(motion);
        assertEquals(WeaponHudMotion.REST, idle(motion));
        var flying = new WeaponHudMotion();
        for (int i = 0; i < 120; i++)
            assertEquals(WeaponHudMotion.REST, flying.step(1f / 60, 0, 0, true, 5.6f, false, 0, 0));
    }

    @Test void jumpAndLandingGiveOppositeBriefImpulses() {
        var motion = new WeaponHudMotion();
        idle(motion);
        motion.step(0.02f, 0, 0, false, 0, false, 8, 0);
        assertTrue(motion.step(0.04f, 0, 0, false, 0, false, 7, 0).y() < -0.3);
        motion.step(0.02f, 0, 0, false, 0, true, 0, 0);
        assertTrue(motion.step(0.04f, 0, 0, false, 0, true, 0, 0).y() > 0.3);
        for (int i = 0; i < 180; i++) idle(motion);
        assertEquals(WeaponHudMotion.REST, idle(motion));
    }

    @Test void shotsKickAndDecayWithoutRequiringAmmoLoss() {
        var motion = new WeaponHudMotion();
        idle(motion);
        motion.step(1f / 60, 0, 0, false, 0, true, 0, 1);
        assertTrue(idle(motion).y() < -1);
        for (int i = 0; i < 60; i++) idle(motion);
        assertEquals(WeaponHudMotion.REST, idle(motion));
    }

    @Test void automaticFireAndCombinedMovementStayWithinLimits() {
        var motion = new WeaponHudMotion();
        for (int i = 0; i < 2000; i++) {
            var pose = motion.step(1f / 144, i * 6, (i % 2) * 90, true, 10, i % 8 < 4, 8, 4);
            assertTrue(Math.abs(pose.x()) <= WeaponHudMotion.MAX_X);
            assertTrue(Math.abs(pose.y()) <= WeaponHudMotion.MAX_Y);
            assertTrue(Math.abs(pose.roll()) <= WeaponHudMotion.MAX_ROLL);
        }
    }

    @Test void consistentMotionAcrossFrameRates() {
        var low = simulateCameraAndSprint(30);
        var high = simulateCameraAndSprint(144);
        assertEquals(low.x(), high.x(), 0.02);
        assertEquals(low.y(), high.y(), 0.02);
        assertEquals(low.roll(), high.roll(), 0.02);
    }

    private static WeaponHudMotion.Pose simulateCameraAndSprint(int fps) {
        var motion = new WeaponHudMotion();
        motion.step(1f / fps, 0, 0, false, 0, true, 0, 0);
        WeaponHudMotion.Pose result = null;
        for (int i = 1; i <= fps * 2; i++)
            result = motion.step(1f / fps, i * 45f / fps, i * 20f / fps, true, 5.6f, true, 0, 0);
        return result;
    }

    @Test void weaponChangeClearsRecoilAndLongGapDiscardsStaleMotion() {
        var motion = new WeaponHudMotion();
        idle(motion);
        motion.step(0.02f, 0, 0, false, 0, true, 0, 1);
        motion.clearRecoil();
        assertEquals(WeaponHudMotion.REST, idle(motion));
        motion.step(0.02f, 30, 20, true, 5.6f, true, 0, 1);
        assertEquals(WeaponHudMotion.REST, motion.step(5, 100, 45, false, 0, true, 0, 0));
        assertEquals(WeaponHudMotion.REST, motion.step(0.02f, 100, 45, false, 0, true, 0, 0));
        assertEquals(WeaponHudMotion.REST, motion.step(Float.NaN, 0, 0, false, 0, true, 0, 0));
    }

    @Test void recoilHitsQuicklyThenReboundsAndStopsAtZero() {
        var motion = new WeaponHudMotion();
        idle(motion);
        var first = motion.step(0.022f, 0, 0, false, 0, true, 0, 1);
        var quarter = motion.step(0.045f, 0, 0, false, 0, true, 0, 0);
        var middle = motion.step(0.045f, 0, 0, false, 0, true, 0, 0);
        var rebound = motion.step(0.09f, 0, 0, false, 0, true, 0, 0);
        var end = motion.step(0.1f, 0, 0, false, 0, true, 0, 0);
        assertTrue(first.y() < -2.5);
        assertTrue(Math.abs(quarter.y()) > Math.abs(first.y()) * 0.75);
        assertTrue(middle.y() > first.y() && middle.y() < 0);
        assertTrue(rebound.y() > 0 && rebound.y() < Math.abs(first.y()) * 0.2);
        assertEquals(WeaponHudMotion.REST, end);
    }
}
