package dev.herrastudio.tacticalactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ActionStateTest {
    @Test void proneHasPriorityOverLean() {
        assertEquals(TacticalActionState.PRONE, TacticalActionState.resolve(true, true, true));
        assertEquals(TacticalActionState.PRONE, TacticalActionState.resolve(true, true, false));
    }
    @Test void leanUsesOnlyOneSideAtATime() {
        assertEquals(TacticalActionState.LEAN_LEFT, TacticalActionState.resolve(false, true, false));
        assertEquals(TacticalActionState.LEAN_RIGHT, TacticalActionState.resolve(false, false, true));
        assertEquals(TacticalActionState.STANDING, TacticalActionState.resolve(false, true, true));
        assertEquals(TacticalActionState.STANDING, TacticalActionState.resolve(false, false, false));
    }
}
