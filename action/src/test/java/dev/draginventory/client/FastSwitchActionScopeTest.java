package dev.draginventory.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FastSwitchActionScopeTest {
    @Test void nestedActionRestoresItsParentAndClearsAfterPlanning() {
        assertNull(FastSwitchActionScope.current());
        try (var draw = FastSwitchActionScope.enter("raise")) {
            assertEquals("raise", FastSwitchActionScope.current());
            try (var reload = FastSwitchActionScope.enter("reload")) {
                assertEquals("reload", FastSwitchActionScope.current());
                assertEquals(0.8f, GwoSwitchTimingBridge.adjust("raise", null, 0.8f));
            }
            assertEquals("raise", FastSwitchActionScope.current());
            assertTrue(FastSwitchTiming.isOwnedSwitch("custom_draw_clip", FastSwitchActionScope.current() + "#fixture"));
        }
        assertNull(FastSwitchActionScope.current());
        assertEquals(0.8f, GwoSwitchTimingBridge.adjust("raise", null, 0.8f));
    }

    @Test void exceptionCannotLeakSwitchTimingIntoTheNextAction() {
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = FastSwitchActionScope.enter("drop")) {
                throw new IllegalStateException("fixture");
            }
        });
        assertNull(FastSwitchActionScope.current());
    }
}
