package dev.draginventory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaminaStateTest {
    @Test void sprintAndJumpHaveIndependentCosts() {
        var stamina = new StaminaState();
        for (int tick = 0; tick < 40; tick++) stamina.tick(true);
        assertEquals(76, stamina.value(), 0.001);
        stamina.jump();
        assertEquals(66, stamina.value(), 0.001);
    }

    @Test void jumpRecoveryWaitsBeforeRefilling() {
        var stamina = new StaminaState();
        stamina.jump();
        for (int tick = 0; tick < 40; tick++) stamina.tick(false);
        assertEquals(90, stamina.value(), 0.001);
        stamina.tick(false);
        assertEquals(90.8, stamina.value(), 0.001);
    }

    @Test void exhaustionClampsAndStopsFurtherConsumption() {
        var stamina = new StaminaState();
        for (int tick = 0; tick < 167; tick++) stamina.tick(true);
        assertEquals(0, stamina.value());
        assertFalse(stamina.jump());
        assertEquals(0, stamina.value());
    }

    @Test void restingRecoversAndClampsToMaximum() {
        var stamina = new StaminaState(0);
        for (int tick = 0; tick < 300; tick++) stamina.tick(false);
        assertEquals(100, stamina.value());
    }

    @Test void newConsumptionRestartsRecoveryDelay() {
        var stamina = new StaminaState();
        stamina.jump();
        for (int tick = 0; tick < 40; tick++) stamina.tick(false);
        stamina.jump();
        for (int tick = 0; tick < 40; tick++) stamina.tick(false);
        assertEquals(80, stamina.value(), 0.001);
    }

    @Test void positionSamplingIgnoresStandingStillAndTeleportation() {
        var stamina = new StaminaState();
        assertFalse(stamina.moved(10, 10));
        assertFalse(stamina.moved(10, 10));
        assertTrue(stamina.moved(10.2, 10));
        assertFalse(stamina.moved(1000, 1000));
        assertFalse(stamina.moved(1000, 1000));
        assertTrue(stamina.moved(1000, 1000.2));
    }

    @Test void storedValuesAreValidated() {
        assertEquals(0, new StaminaState(-30).value());
        assertEquals(100, new StaminaState(300).value());
        assertEquals(100, new StaminaState(Float.NaN).value());
        assertEquals(100, new StaminaState(Float.POSITIVE_INFINITY).value());
        assertEquals(36, new StaminaState(36).value());
    }

    @Test void playersDoNotShareStamina() {
        var first = new StaminaState();
        var second = new StaminaState();
        first.jump();
        assertEquals(90, first.value());
        assertEquals(100, second.value());
    }

    @Test void redStaminaBlocksStartingButAllowsExistingSprint() {
        var stamina = new StaminaState(20);
        assertFalse(stamina.canSprint(false));
        assertTrue(stamina.canSprint(true));
        stamina.tick(true);
        assertTrue(stamina.canSprint(true));
        assertFalse(stamina.canSprint(false));
        assertTrue(new StaminaState(20.01f).canSprint(false));
    }

    @Test void lastPositiveStaminaAllowsJumpThenBlocksAllActions() {
        var stamina = new StaminaState(1);
        assertTrue(stamina.canJump());
        assertTrue(stamina.jump());
        assertEquals(0, stamina.value());
        assertFalse(stamina.canJump());
        assertFalse(stamina.canSprint(false));
        assertFalse(stamina.canSprint(true));
    }

    @Test void rejectedJumpsDoNotPostponeRecovery() {
        var stamina = new StaminaState(0);
        for (int tick = 0; tick < 40; tick++) {
            assertFalse(stamina.jump());
            stamina.tick(false);
            assertEquals(0, stamina.value());
        }
        stamina.tick(false);
        assertEquals(0.8, stamina.value(), 0.001);
    }

    @Test void recoveryUnlocksJumpBeforeNewSprint() {
        var stamina = new StaminaState(0);
        for (int tick = 0; tick < 41; tick++) stamina.tick(false);
        assertTrue(stamina.canJump());
        assertFalse(stamina.canSprint(false));
        for (int tick = 0; tick < 26; tick++) stamina.tick(false);
        assertTrue(stamina.canSprint(false));
    }

    @Test void activeSprintIsAllowedAllTheWayToEmpty() {
        var stamina = new StaminaState(20);
        while (stamina.value() > 0) {
            assertTrue(stamina.canSprint(true));
            stamina.tick(true);
        }
        assertFalse(stamina.canSprint(true));
    }
}
