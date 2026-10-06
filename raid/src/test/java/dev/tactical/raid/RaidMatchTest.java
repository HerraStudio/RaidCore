package dev.tactical.raid;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaidMatchTest {
    private final UUID alpha = UUID.randomUUID(), beta = UUID.randomUUID();
    private final RaidMatch match = new RaidMatch(UUID.randomUUID(), "工厂", "minecraft:overworld", 1800);

    private void joinBoth() {
        assertNotNull(match.join(alpha, "alpha"));
        assertNotNull(match.join(beta, "beta"));
    }
    private void startBoth() { joinBoth(); assertTrue(match.beginStart(2)); assertTrue(match.completeStart()); }

    @Test void participantsShareAnIdAndWaitingDoesNotStartTheClock() {
        joinBoth();
        assertEquals(match.id, match.participants().get(alpha).session().id);
        assertEquals(match.id, match.participants().get(beta).session().id);
        for (int i = 0; i < 100; i++) {
            assertFalse(match.tick());
            assertFalse(match.participants().get(alpha).session().tickInMatch("exit", 1));
        }
        assertEquals(0, match.elapsedTicks());
        assertEquals(0, match.activePlayers());
        assertEquals(36_000, match.remainingTicks());
        assertEquals(RaidSession.Status.WAITING, match.participants().get(alpha).session().status());
    }

    @Test void startRequiresEnoughPlayersAndCompletesAsOneTransition() {
        match.join(alpha, "alpha");
        assertFalse(match.beginStart(2));
        match.join(beta, "beta");
        assertTrue(match.beginStart(2));
        assertEquals(RaidMatch.Phase.STARTING, match.phase());
        assertFalse(match.tick());
        assertTrue(match.completeStart());
        assertEquals(2, match.activePlayers());
        assertEquals(RaidMatch.Phase.IN_RAID, match.phase());
        assertFalse(match.completeStart());
        assertFalse(match.beginStart(2));
    }

    @Test void eachTickAdvancesTheSharedClockOnceForBothPlayers() {
        startBoth();
        for (int i = 0; i < 100; i++) {
            match.tick();
            match.participants().get(alpha).session().tickInMatch(null, 0);
            match.participants().get(beta).session().tickInMatch(null, 0);
        }
        assertEquals(100, match.elapsedTicks());
        assertEquals(35_900, match.remainingTicks());
        assertEquals(100, match.participants().get(alpha).session().elapsedTicks());
    }

    @Test void firstPlayerEndingDoesNotEndTheRoundOrAllowTheirReentry() {
        startBoth();
        assertTrue(match.finishParticipant(alpha, RaidSession.Outcome.EXTRACTED));
        assertEquals(RaidMatch.Phase.IN_RAID, match.phase());
        assertEquals(1, match.activePlayers());
        assertNull(match.join(alpha, "alpha"));
        assertNull(match.join(UUID.randomUUID(), "gamma"));
        assertFalse(match.leaveWaiting(alpha));
        assertTrue(match.contains(alpha));
        assertTrue(match.finishParticipant(beta, RaidSession.Outcome.DEAD));
        assertEquals(RaidMatch.Phase.FINISHED, match.phase());
        assertFalse(match.tick());
        assertFalse(match.finishParticipant(alpha, RaidSession.Outcome.DEAD));
        assertEquals(RaidSession.Outcome.EXTRACTED, match.participants().get(alpha).session().outcome());
    }

    @Test void sharedExpirySettlesEveryoneAtTheSameTickAndPreservesEarlierResults() {
        var shortMatch = new RaidMatch(UUID.randomUUID(), "工厂", "minecraft:overworld", 1);
        shortMatch.join(alpha, "alpha"); shortMatch.join(beta, "beta");
        assertTrue(shortMatch.beginStart(2)); shortMatch.completeStart();
        shortMatch.finishParticipant(alpha, RaidSession.Outcome.EXTRACTED);
        for (int i = 0; i < 19; i++) assertFalse(shortMatch.tick());
        assertTrue(shortMatch.tick());
        assertEquals(20, shortMatch.elapsedTicks());
        assertEquals(0, shortMatch.remainingTicks());
        assertEquals(RaidSession.Outcome.EXTRACTED, shortMatch.participants().get(alpha).session().outcome());
        assertEquals(RaidSession.Outcome.TIMED_OUT, shortMatch.participants().get(beta).session().outcome());
        assertEquals(20, shortMatch.participants().get(beta).session().elapsedTicks());
        assertFalse(shortMatch.tick());
    }

    @Test void allActivePlayersExpireTogether() {
        var shortMatch = new RaidMatch(UUID.randomUUID(), "工厂", "minecraft:overworld", 1);
        shortMatch.join(alpha, "alpha"); shortMatch.join(beta, "beta");
        shortMatch.beginStart(2); shortMatch.completeStart();
        for (int i = 0; i < 20; i++) shortMatch.tick();
        assertEquals(RaidSession.Outcome.TIMED_OUT, shortMatch.participants().get(alpha).session().outcome());
        assertEquals(RaidSession.Outcome.TIMED_OUT, shortMatch.participants().get(beta).session().outcome());
    }

    @Test void waitingPlayersCanLeaveAndFreeTheirSpawn() {
        joinBoth();
        assertNull(match.join(UUID.randomUUID(), "beta"));
        assertTrue(match.leaveWaiting(alpha));
        assertNotNull(match.join(UUID.randomUUID(), "alpha"));
        assertFalse(match.contains(alpha));
        assertNull(match.join(beta, "gamma"));
    }

    @Test void failedStartReturnsToWaitingWithoutConsumingTime() {
        joinBoth(); match.beginStart(2); match.cancelStart();
        assertEquals(RaidMatch.Phase.WAITING, match.phase());
        assertEquals(0, match.elapsedTicks());
        assertTrue(match.beginStart(2));
        assertTrue(match.completeStart());
    }

    @Test void abortIsIdempotentAndNeverOverwritesACommittedOutcome() {
        startBoth(); match.finishParticipant(alpha, RaidSession.Outcome.EXTRACTED);
        match.abort(); long finished = match.finishedAt(); match.abort();
        assertEquals(RaidMatch.Phase.FINISHED, match.phase());
        assertEquals(finished, match.finishedAt());
        assertEquals(RaidSession.Outcome.EXTRACTED, match.participants().get(alpha).session().outcome());
        assertEquals(RaidSession.Outcome.ABORTED, match.participants().get(beta).session().outcome());
    }

    @Test void participantCollectionCannotBeChangedOutsideTheMatch() {
        joinBoth();
        assertThrows(UnsupportedOperationException.class, () -> match.participants().clear());
    }

    @Test void waitingPlayersCannotAccumulateKillsOrExtractionTime() {
        joinBoth();
        var player = match.participants().get(alpha).session();
        player.countKill();
        assertFalse(player.tick("exit", 1));
        assertFalse(player.tickInMatch("exit", 1, 50));
        assertEquals(0, player.kills());
        assertEquals(0, player.elapsedTicks());
        assertFalse(player.inRaid());
    }

    @Test void cancelledLastParticipantCanBeReplacedBeforeStart() {
        joinBoth();
        match.leaveWaiting(alpha); match.leaveWaiting(beta);
        assertTrue(match.participants().isEmpty());
        assertNotNull(match.join(alpha, "alpha"));
        assertFalse(match.beginStart(2));
    }

    @Test void aTerminalWaitingRecordCannotProduceAPartialStart() {
        joinBoth();
        match.participants().get(alpha).session().finish(RaidSession.Outcome.ABORTED);
        assertFalse(match.beginStart(2));
        assertEquals(RaidMatch.Phase.WAITING, match.phase());
        assertFalse(match.participants().get(beta).session().inRaid());
    }
}
