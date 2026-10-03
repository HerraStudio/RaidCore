package dev.draginventory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GestureTest {
    private final Gesture gesture = new Gesture();
    private final Object slot = new Object();

    private void click(long time) {
        assertFalse(gesture.press(slot, 10, 10, time, true));
        gesture.release(slot, time + 40);
    }

    @Test void shortClickDoesNotStartDragging() {
        gesture.press(slot, 10, 10, 1000, true);
        assertFalse(gesture.update(11, 11, 1040));
        gesture.release(slot, 1050);
        assertFalse(gesture.active());
    }
    @Test void heldButtonStartsDragAtThresholdOnlyOnce() {
        gesture.press(slot, 10, 10, 1000, true);
        assertFalse(gesture.update(10, 10, 1179));
        assertTrue(gesture.update(10, 10, 1180));
        assertFalse(gesture.update(10, 10, 1200));
        assertTrue(gesture.dragging());
    }
    @Test void motionStartsDragWithoutWaiting() {
        gesture.press(slot, 10, 10, 1000, true);
        assertTrue(gesture.update(14, 10, 1001));
    }
    @Test void jitterDoesNotStartDrag() {
        gesture.press(slot, 10, 10, 1000, true);
        assertFalse(gesture.update(12, 12, 1010));
    }
    @Test void doubleClickQuickMovesWithoutPickingUp() {
        click(1000);
        assertTrue(gesture.press(slot, 10, 10, 1200, true));
        assertTrue(gesture.quickMove());
        assertFalse(gesture.update(40, 40, 1500));
        assertFalse(gesture.dragging());
    }
    @Test void secondClickAtTimeoutIsNotDoubleClick() {
        click(1000);
        assertFalse(gesture.press(slot, 10, 10, 1250, true));
    }
    @Test void otherSlotDoesNotQuickMove() {
        click(1000);
        assertFalse(gesture.press(new Object(), 10, 10, 1100, true));
    }
    @Test void draggingDoesNotSeedDoubleClick() {
        gesture.press(slot, 10, 10, 1000, true);
        gesture.update(20, 10, 1010);
        gesture.release(slot, 1030);
        assertFalse(gesture.press(slot, 10, 10, 1100, true));
    }
    @Test void outsideReleaseDoesNotSeedDoubleClick() {
        gesture.press(slot, 10, 10, 1000, true);
        gesture.release(null, 1040);
        assertFalse(gesture.press(slot, 10, 10, 1100, true));
    }
    @Test void cursorStackCannotQuickMove() {
        click(1000);
        assertFalse(gesture.press(slot, 10, 10, 1100, false));
    }
    @Test void tripleClickDoesNotQuickMoveTwice() {
        click(1000);
        assertTrue(gesture.press(slot, 10, 10, 1100, true));
        gesture.release(slot, 1140);
        assertFalse(gesture.press(slot, 10, 10, 1200, true));
    }
    @Test void screenCloseOrOtherButtonClearsCandidate() {
        click(1000);
        gesture.reset();
        assertFalse(gesture.press(slot, 10, 10, 1100, true));
    }
    @Test void updateAfterReleaseCannotPickUp() {
        click(1000);
        assertFalse(gesture.update(30, 30, 2000));
    }
    @Test void emptySpaceNeverDoubleClicks() {
        gesture.press(null, 10, 10, 1000, true);
        gesture.release(null, 1040);
        assertFalse(gesture.press(null, 10, 10, 1100, true));
    }
    @Test void releaseInDifferentSlotDoesNotSeedDoubleClick() {
        Object other = new Object();
        gesture.press(slot, 10, 10, 1000, true);
        gesture.release(other, 1040);
        assertFalse(gesture.press(other, 10, 10, 1100, true));
    }
}
