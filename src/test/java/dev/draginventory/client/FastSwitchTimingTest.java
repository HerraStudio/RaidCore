package dev.draginventory.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FastSwitchTimingTest {
    @Test void defaultM4SwitchTimesAreHalved() {
        assertEquals(0.4f, FastSwitchTiming.duration(0.8f, 0, -1, 1, 2), 0.00001);
        assertEquals(0.35f, FastSwitchTiming.duration(0.7f, 0, -1, 1, 2), 0.00001);
    }

    @Test void cutClipWindowAndAuthoredSpeedAreBothAccountedFor() {
        assertEquals(0.3f, FastSwitchTiming.duration(1, 0.2f, 0.8f, 1, 2), 0.00001);
        assertEquals(0.15f, FastSwitchTiming.duration(1, 0.2f, 0.8f, 2, 2), 0.00001);
    }

    @Test void knownDrawAndDropVariantsAreRecognizedWithoutMatchingOtherActions() {
        for (String state : new String[]{"raise", "raise_first", "raise_first_barcomp", "drop", "drop_empty", "draw", "holster"})
            assertTrue(FastSwitchTiming.isSwitchState(state));
        for (String state : new String[]{"reload", "reload_empty", "fire", "fire_post", "inspect", "melee", "sprint_out", "idle", ""})
            assertFalse(FastSwitchTiming.isSwitchState(state));
    }

    @Test void authoritativeOwnerProtectsSharedReloadAndMeleeClips() {
        assertTrue(FastSwitchTiming.isOwnedSwitch("custom_draw", "raise#12"));
        assertTrue(FastSwitchTiming.isOwnedSwitch("custom_drop", "drop#8"));
        assertFalse(FastSwitchTiming.isOwnedSwitch("raise", "reload#3"));
        assertFalse(FastSwitchTiming.isOwnedSwitch("drop", "melee#4"));
        assertTrue(FastSwitchTiming.isOwnedSwitch("raise_first", ""));
    }

    @Test void invalidOrEmptyWindowsRetainTheNativeMinimum() {
        assertEquals(0.05f, FastSwitchTiming.duration(0.01f, 0, -1, 1, 2));
        assertEquals(0.05f, FastSwitchTiming.duration(1, 0.8f, 0.2f, 1, 2));
        assertTrue(Float.isFinite(FastSwitchTiming.duration(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Double.NaN)));
    }
}
