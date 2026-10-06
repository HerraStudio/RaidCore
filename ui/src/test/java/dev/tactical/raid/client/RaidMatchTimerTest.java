package dev.tactical.raid.client;

import dev.draginventory.client.map.MapMatchTimer;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaidMatchTimerTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final RaidMatchTimer timer = new RaidMatchTimer(now::get);

    @Test void serverSnapshotsDriveTheThirtyMinuteClockWithCeilingRounding() {
        timer.sync(36_000);
        assertTrue(timer.isActive());
        assertEquals("30:00", MapMatchTimer.formatSeconds(timer.remainingSeconds()));
        timer.sync(35_981);
        assertEquals(1800, timer.remainingSeconds());
        now.addAndGet(50);
        assertEquals("29:59", MapMatchTimer.formatSeconds(timer.remainingSeconds()));
    }

    @Test void latePacketsOrPausedServerCannotInventMatchCompletion() {
        timer.sync(21);
        assertEquals(2, timer.remainingSeconds());
        now.addAndGet(50);
        assertEquals(1, timer.remainingSeconds());
        now.addAndGet(30 * 60 * 1000);
        assertEquals(1, timer.remainingSeconds());
        timer.sync(21);
        assertEquals(2, timer.remainingSeconds());
    }

    @Test void predictionStopsAfterFiveTicksWithoutAnotherSnapshot() {
        timer.sync(26);
        now.addAndGet(60 * 1000);
        assertEquals(2, timer.remainingSeconds());
        timer.sync(20);
        assertEquals(1, timer.remainingSeconds());
    }

    @Test void settlementOrDisconnectClearsClockAndNextRaidStartsFresh() {
        assertEquals(-1, timer.remainingSeconds());
        timer.sync(1);
        now.addAndGet(1000);
        assertEquals(1, timer.remainingSeconds());
        timer.clear();
        assertFalse(timer.isActive());
        assertEquals(-1, timer.remainingSeconds());
        timer.sync(36_000);
        assertEquals(1800, timer.remainingSeconds());
    }

    @Test void clockMovingBackwardsDoesNotAddTime() {
        timer.sync(20);
        now.set(0);
        assertEquals(1, timer.remainingSeconds());
    }
}
