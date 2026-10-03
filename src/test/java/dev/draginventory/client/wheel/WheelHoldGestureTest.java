package dev.draginventory.client.wheel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WheelHoldGestureTest {
    @Test void oneMillisecondTapDoesNotOpenOnRelease() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 0));
        assertFalse(hold.update(false, true, 1));
        assertFalse(hold.update(false, true, 2));
    }

    @Test void tenMillisecondTapDoesNotOpenOnRelease() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 100));
        assertFalse(hold.update(false, true, 110));
    }

    @Test void fortyNineMillisecondTapDoesNotOpenOnRelease() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 100));
        assertFalse(hold.update(true, true, 148));
        assertFalse(hold.update(false, true, 149));
    }

    @Test void pressAndReleaseObservedWithinSameTickDoesNotOpen() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(false, true, 0));
        assertFalse(hold.update(true, true, 5));
        assertFalse(hold.update(false, true, 5));
        assertFalse(hold.update(false, true, 50));
    }

    @Test void fiftyMillisecondHoldOpensOnceAndReleaseDoesNotOpenAgain() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 200));
        assertFalse(hold.update(true, true, 249));
        assertTrue(hold.update(true, true, 250));
        assertFalse(hold.update(true, true, 500));
        assertFalse(hold.update(false, true, 600));
    }

    @Test void repeatedUpdatesDoNotRestartPendingHold() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 0));
        assertFalse(hold.update(true, true, 10));
        assertFalse(hold.update(true, true, 20));
        assertFalse(hold.update(true, true, 49));
        assertTrue(hold.update(true, true, 50));
    }

    @Test void pressingInMenuCannotOpenWhenMenuClosesUntilAnotherPress() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, false, 0));
        assertFalse(hold.update(true, true, 200));
        assertFalse(hold.update(false, true, 300));
        assertFalse(hold.update(true, true, 400));
        assertTrue(hold.update(true, true, 450));
    }

    @Test void losingFocusDuringHoldCancelsRatherThanCommittingOnReturn() {
        var hold = new WheelHoldGesture();
        hold.update(true, true, 0);
        assertFalse(hold.update(true, false, 100));
        assertFalse(hold.update(true, true, 1000));
        hold.update(false, true, 1100);
        hold.update(true, true, 1200);
        assertTrue(hold.update(true, true, 1250));
    }

    @Test void cancelingOpenWheelRequiresReleaseBeforeReopening() {
        var hold = new WheelHoldGesture();
        hold.suspend(true);
        assertFalse(hold.update(true, true, 1000));
        hold.update(false, true, 1100);
        hold.update(true, true, 1200);
        assertTrue(hold.update(true, true, 1250));
    }

    @Test void releasingWhileIneligibleCancelsShortTap() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 0));
        assertFalse(hold.update(false, false, 10));
        assertFalse(hold.update(false, true, 20));
        assertFalse(hold.update(true, true, 30));
        assertTrue(hold.update(true, true, 80));
    }

    @Test void suspendingPendingTapPreventsItsReleaseFromReopening() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 0));
        hold.suspend(true);
        assertFalse(hold.update(false, true, 10));
        assertFalse(hold.update(true, true, 20));
        assertTrue(hold.update(true, true, 70));
    }

    @Test void suspendingReleasedKeyAllowsNextFreshPress() {
        var hold = new WheelHoldGesture();
        hold.suspend(true);
        hold.suspend(false);
        assertFalse(hold.update(true, true, 100));
        assertTrue(hold.update(true, true, 150));
    }

    @Test void negativeInitialTimestampIsAValidClockOrigin() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, -100));
        assertFalse(hold.update(true, true, -51));
        assertTrue(hold.update(true, true, -50));
    }

    @Test void backwardClockRestartsPendingHold() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 100));
        assertFalse(hold.update(true, true, 80));
        assertFalse(hold.update(true, true, 129));
        assertTrue(hold.update(true, true, 130));
    }

    @Test void resetCancelsPendingTap() {
        var hold = new WheelHoldGesture();
        assertFalse(hold.update(true, true, 0));
        hold.reset();
        assertFalse(hold.update(false, true, 10));
    }
}
