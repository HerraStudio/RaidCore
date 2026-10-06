package dev.tactical.raid;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaidSessionTest {
    private RaidSession session() { return new RaidSession(UUID.randomUUID(), "工厂"); }

    @Test void defaultMatchLastsThirtyMinutesAndExpiresExactlyOnce() {
        var session = session();
        assertEquals(36_000, session.matchDurationTicks());
        assertEquals(36_000, session.remainingMatchTicks());
        for (int i = 0; i < 35_999; i++) assertFalse(session.tick(null, 0));
        assertTrue(session.active());
        assertEquals(1, session.remainingMatchTicks());
        assertFalse(session.tick(null, 0));
        assertEquals(RaidSession.Status.SETTLED, session.status());
        assertEquals(RaidSession.Outcome.TIMED_OUT, session.outcome());
        assertEquals(0, session.remainingMatchTicks());
        assertEquals(36_000, session.elapsedTicks());
        assertFalse(session.finish(RaidSession.Outcome.EXTRACTED));
        assertFalse(session.finish(RaidSession.Outcome.DEAD));
        assertFalse(session.tick("north", 1));
        assertEquals(36_000, session.elapsedTicks());
    }

    @Test void timeoutWinsWhenExtractionWouldCompleteOnTheDeadline() {
        var session = new RaidSession(UUID.randomUUID(), "工厂", 1);
        for (int i = 0; i < 19; i++) assertFalse(session.tick("north", 20));
        assertEquals(1, session.remainingTicks());
        assertFalse(session.tick("north", 20));
        assertEquals(RaidSession.Outcome.TIMED_OUT, session.outcome());
        assertEquals("", session.zone());
        assertEquals(0, session.remainingTicks());
    }

    @Test void extractionBeforeTheDeadlineKeepsItsResultAndStopsTheMatchClock() {
        var session = new RaidSession(UUID.randomUUID(), "工厂", 1);
        for (int i = 0; i < 18; i++) session.tick("north", 19);
        assertTrue(session.tick("north", 19));
        for (int i = 0; i < 25; i++) assertFalse(session.tick(null, 0));
        assertEquals(RaidSession.Outcome.EXTRACTED, session.outcome());
        assertEquals(19, session.elapsedTicks());
        assertEquals(1, session.remainingMatchTicks());
    }

    @Test void extractionResetsNeverRestartTheMatchClock() {
        var session = new RaidSession(UUID.randomUUID(), "工厂", 60);
        session.tick("north", 100);
        session.tick(null, 0);
        session.tick("south", 200);
        assertEquals(1197, session.remainingMatchTicks());
        assertEquals(199, session.remainingTicks());
    }

    @Test void restoredMatchKeepsItsDurationAndElapsedTime() {
        var session = RaidSession.restore(UUID.randomUUID(), "工厂", RaidSession.Status.EXTRACTING, null,
                "north", 10, 20, 59, 2, 3);
        assertEquals(60, session.matchDurationTicks());
        assertEquals(1, session.remainingMatchTicks());
        session.tick(null, 0);
        assertEquals(RaidSession.Outcome.TIMED_OUT, session.outcome());
        assertEquals(60, session.elapsedTicks());
    }

    @Test void oldOrInvalidDurationUsesThirtyMinutesAndLargeDurationIsBounded() {
        assertEquals(36_000, new RaidSession(UUID.randomUUID(), "工厂", 0).matchDurationTicks());
        assertEquals(36_000, new RaidSession(UUID.randomUUID(), "工厂", -1).matchDurationTicks());
        assertEquals(1_728_000, new RaidSession(UUID.randomUUID(), "工厂", Integer.MAX_VALUE).matchDurationTicks());
        var restored = RaidSession.restore(UUID.randomUUID(), "工厂", RaidSession.Status.ACTIVE, null,
                "", 0, 0, 400, 0);
        assertEquals(35_600, restored.remainingMatchTicks());
    }

    @Test void twentySecondExtractionRequiresFourHundredContinuousTicks() {
        var session = session();
        for (int i = 0; i < 399; i++) assertFalse(session.tick("north", 400));
        assertEquals(RaidSession.Status.EXTRACTING, session.status());
        assertEquals(1, session.remainingTicks());
        assertTrue(session.tick("north", 400));
        assertEquals(RaidSession.Outcome.EXTRACTED, session.outcome());
        assertEquals(400, session.elapsedTicks());
    }

    @Test void leavingAndReenteringDiscardsAllPreviousProgress() {
        var session = session();
        for (int i = 0; i < 390; i++) session.tick("north", 400);
        session.tick(null, 0);
        assertEquals(RaidSession.Status.ACTIVE, session.status());
        assertEquals("", session.zone());
        session.tick("north", 400);
        assertEquals(399, session.remainingTicks());
        assertEquals(392, session.elapsedTicks());
    }

    @Test void movingToAnotherZoneStartsThatZonesFullCountdown() {
        var session = session();
        session.tick("north", 400); session.tick("north", 400);
        session.tick("south", 100);
        assertEquals("south", session.zone()); assertEquals(99, session.remainingTicks());
    }

    @Test void changingConfiguredDurationCannotReusePriorProgress() {
        var session = session();
        for (int i = 0; i < 90; i++) session.tick("north", 100);
        assertFalse(session.tick("north", 50)); assertEquals(49, session.remainingTicks());
    }

    @Test void deathCannotBecomeAnExtractionOrBeCommittedTwice() {
        var session = session(); session.tick("north", 2);
        assertTrue(session.finish(RaidSession.Outcome.DEAD));
        assertFalse(session.finish(RaidSession.Outcome.EXTRACTED));
        assertFalse(session.tick("north", 2));
        assertEquals(RaidSession.Outcome.DEAD, session.outcome());
        assertEquals(1, session.elapsedTicks());
    }

    @Test void extractionCannotBeOverwrittenByLateDeathOrLogout() {
        var session = session(); assertTrue(session.tick("north", 1));
        assertFalse(session.finish(RaidSession.Outcome.DEAD)); assertFalse(session.finish(RaidSession.Outcome.ABORTED));
        assertEquals(RaidSession.Outcome.EXTRACTED, session.outcome());
    }

    @Test void killsStopCountingAfterSettlement() {
        var session = session(); session.countKill(); session.countKill();
        session.finish(RaidSession.Outcome.DEAD); session.countKill();
        assertEquals(2, session.kills());
    }

    @Test void restoredActiveSessionNeverKeepsCountdownProgress() {
        var id = UUID.randomUUID();
        var session = RaidSession.restore(id, "工厂", RaidSession.Status.EXTRACTING, null, "north", 399, 400, 650, 4);
        assertEquals(id, session.id); assertEquals(RaidSession.Status.ACTIVE, session.status());
        assertEquals(0, session.requiredTicks()); assertEquals(650, session.elapsedTicks()); assertEquals(4, session.kills());
    }

    @Test void restoredSettlementPreservesFinalOutcomeAndElapsedTime() {
        var session = RaidSession.restore(UUID.randomUUID(), "工厂", RaidSession.Status.SETTLED, RaidSession.Outcome.EXTRACTED,
                "north", 400, 400, 900, 3);
        assertEquals(RaidSession.Outcome.EXTRACTED, session.outcome()); assertEquals(900, session.elapsedTicks());
        assertFalse(session.tick("north", 400)); assertEquals(3, session.kills());
    }

    @Test void squareBoundsUseBothHorizontalAxesAndLimitedHeight() {
        var bounds = RaidBounds.square(10, 64, 20, 3);
        assertTrue(bounds.contains(7, 63, 17)); assertTrue(bounds.contains(13, 68, 23));
        assertFalse(bounds.contains(13.001, 64, 20)); assertFalse(bounds.contains(10, 64, 23.001));
        assertFalse(bounds.contains(10, 62.99, 20)); assertFalse(bounds.contains(10, 68.001, 20));
        assertFalse(bounds.contains(Double.NaN, 64, 20));
    }

    @Test void invalidZoneBoundsAndRadiiAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RaidBounds.square(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> RaidBounds.square(0, 0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RaidBounds(1, 0, 0, 0, 1, 1));
    }
}
