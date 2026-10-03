package dev.herrastudio.tacticalactions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundedCrouchTest {
    @Test void landingNeverReusesHeldCrouchAndFreshGroundPressWorks() {
        var state = new GroundedCrouch();
        assertTrue(state.update(true, true, false, true, false).crouch());
        assertFalse(state.update(false, true, true, true, false).crouch());
        assertFalse(state.update(true, true, false, true, false).crouch());
        state.update(true,true,false,false,false);
        assertTrue(state.update(true,true,false,true,false).crouch());
    }
    @Test void airbornePressAndReleaseCannotBufferCrouchOnLanding() {
        var state = new GroundedCrouch();
        state.update(false,true,false,true,false);
        assertFalse(state.update(true,true,false,true,false).crouch());
        state.update(false,true,false,false,false);
        assertFalse(state.update(true,true,false,true,false).crouch());
    }
    @Test void crouchJumpHoldStaysStandingForAllAirAndLandingTicks() {
        var state = new GroundedCrouch();
        state.update(true,true,true,true,true);
        assertTrue(state.update(true,true,false,true,false).jump());
        for(int i=0;i<20;i++) assertFalse(state.update(false,true,false,true,false).crouch());
        for(int i=0;i<20;i++) assertFalse(state.update(true,true,false,true,false).crouch());
    }
    @Test void crouchJumpStandsForATickThenExecutesEvenIfJumpWasTapped() {
        var state = new GroundedCrouch();
        assertEquals(new GroundedCrouch.Result(false, false), state.update(true, true, true, true, true));
        assertEquals(new GroundedCrouch.Result(false, true), state.update(true, true, false, true, false));
        assertFalse(state.update(false, true, false, true, false).crouch());
    }
    @Test void ceilingBlocksCrouchJumpWithoutQueuingAnUnexpectedJump() {
        var state = new GroundedCrouch();
        assertEquals(new GroundedCrouch.Result(true, false), state.update(true, false, true, true, true));
        assertEquals(new GroundedCrouch.Result(true, false), state.update(true, true, true, true, false));
    }
    @Test void lostSupportAndContextResetCancelPendingJump() {
        var state = new GroundedCrouch();
        state.update(true, true, true, true, true);
        state.update(false, true, false, true, false);
        assertFalse(state.update(true, true, false, true, false).jump());
        state.update(true, true,true,true,true);
        state.reset();
        assertFalse(state.update(true,true,false,false,false).jump());
    }
    @Test void ordinaryStandingJumpHasNoAddedDelay() {
        assertEquals(new GroundedCrouch.Result(false,true),new GroundedCrouch().update(true,true,false,false,true));
    }
}
