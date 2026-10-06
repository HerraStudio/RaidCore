package dev.tactical.raid.client;

import dev.draginventory.client.map.MapMatchTimer;
import java.util.function.LongSupplier;

/** Displays the server's match clock; prediction never decides that a raid has expired. */
public final class RaidMatchTimer implements MapMatchTimer.Provider {
    private final LongSupplier clock;
    private long remainingTicks;
    private long syncedAt;
    private boolean active;

    public RaidMatchTimer() { this(() -> System.nanoTime() / 1_000_000L); }
    RaidMatchTimer(LongSupplier clock) { this.clock = clock; }

    public void sync(long ticks) {
        remainingTicks = Math.max(0, ticks);
        syncedAt = clock.getAsLong();
        active = true;
    }

    public boolean isActive() { return active; }

    public void clear() {
        active = false;
        remainingTicks = syncedAt = 0;
    }

    @Override public long remainingSeconds() {
        if (!active) return -1;
        // RaidManager sends an update every five server ticks. Freeze after that window
        // if a packet is late or the server is paused, and wait for its settlement.
        long elapsed = Math.max(0, clock.getAsLong() - syncedAt);
        long prediction = Math.min(5, elapsed / 50);
        return (Math.max(1, remainingTicks - prediction) + 19) / 20;
    }
}
