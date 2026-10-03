package dev.draginventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.draginventory.client.WeaponSwitchAnimation;
import org.junit.jupiter.api.Test;

class WeaponSwitchAnimationTest {
    @Test void transitionsEaseInAndOutWithExactEndpoints() {
        assertEquals(0f, WeaponSwitchAnimation.progress(0), 0.0001f);
        assertTrue(WeaponSwitchAnimation.progress(60) < 0.25f);
        assertEquals(0.5f, WeaponSwitchAnimation.progress(120), 0.0001f);
        assertTrue(WeaponSwitchAnimation.progress(180) > 0.75f);
        assertEquals(1f, WeaponSwitchAnimation.progress(WeaponSwitchAnimation.DURATION_MS), 0.0001f);
    }

    @Test void movementDirectionIsIndependentFromSelectionCommit() {
        assertTrue(WeaponSwitchAnimation.offsetAt(0, 0, 3) < 0);
        assertTrue(WeaponSwitchAnimation.offsetAt(0, 3, 0) > 0);
        assertEquals(0f, WeaponSwitchAnimation.offsetAt(WeaponSwitchAnimation.DURATION_MS, 0, 3), 0.0001f);
    }

    @Test void mainPanelArrivesFromTheSideAndBelowWhileGrowingToNormalSize() {
        var start = WeaponSwitchAnimation.mainPoseAt(0, 0, 1);
        var middle = WeaponSwitchAnimation.mainPoseAt(120, 0, 1);
        var end = WeaponSwitchAnimation.mainPoseAt(240, 0, 1);
        assertEquals(-24f, start.x(), 0.0001f);
        assertEquals(7f, start.y(), 0.0001f);
        assertEquals(0.84f, start.scale(), 0.0001f);
        assertTrue(middle.x() > start.x() && middle.x() < end.x());
        assertTrue(middle.y() < start.y() && middle.y() > end.y());
        assertTrue(middle.scale() > start.scale() && middle.scale() < end.scale());
        assertEquals(0f, end.x(), 0.0001f);
        assertEquals(0f, end.y(), 0.0001f);
        assertEquals(1f, end.scale(), 0.0001f);
        assertEquals(end, WeaponSwitchAnimation.mainPoseAt(500, 0, 1));
    }

    @Test void mainPanelReversesForPreviousWeaponAndStaysStillWithoutAWeaponChange() {
        assertEquals(24f, WeaponSwitchAnimation.mainPoseAt(0, 2, 0).x(), 0.0001f);
        var rest = new WeaponSwitchAnimation.MainPose(0, 0, 1);
        assertEquals(rest, WeaponSwitchAnimation.mainPoseAt(0, 1, 1));
        assertEquals(rest, WeaponSwitchAnimation.mainPoseAt(0, -1, 0));
        assertEquals(rest, WeaponSwitchAnimation.mainPoseAt(0, 1, 5));
    }
}
