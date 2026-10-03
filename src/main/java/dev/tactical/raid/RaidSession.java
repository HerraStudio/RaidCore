package dev.tactical.raid;

import java.util.UUID;

/** Server-owned state machine; an outcome can be committed only once. */
public final class RaidSession {
    public enum Status { ACTIVE, EXTRACTING, SETTLED }
    public enum Outcome { EXTRACTED, DEAD, ABORTED }

    public final UUID id;
    public final String map;
    private Status status = Status.ACTIVE;
    private Outcome outcome;
    private String zone = "";
    private int extractionTicks, requiredTicks, kills;
    private long elapsedTicks;

    public RaidSession(UUID id, String map) {
        this.id = id;
        this.map = map;
    }

    public Status status() { return status; }
    public Outcome outcome() { return outcome; }
    public String zone() { return zone; }
    public int requiredTicks() { return requiredTicks; }
    public int remainingTicks() { return Math.max(0, requiredTicks - extractionTicks); }
    public int extractionTicks() { return extractionTicks; }
    public long elapsedTicks() { return elapsedTicks; }
    public int kills() { return kills; }
    public boolean active() { return status != Status.SETTLED; }

    /** A new zone starts a fresh countdown; leaving the area always discards progress. */
    public boolean tick(String nextZone, int durationTicks) {
        if (!active()) return false;
        elapsedTicks++;
        if (nextZone == null || nextZone.isEmpty()) {
            resetExtraction();
            return false;
        }
        int duration = Math.max(1, durationTicks);
        if (!nextZone.equals(zone) || duration != requiredTicks) {
            zone = nextZone;
            extractionTicks = 0;
            requiredTicks = duration;
        }
        status = Status.EXTRACTING;
        extractionTicks++;
        return extractionTicks >= requiredTicks && finish(Outcome.EXTRACTED);
    }

    public void resetExtraction() {
        if (!active()) return;
        status = Status.ACTIVE;
        zone = "";
        extractionTicks = requiredTicks = 0;
    }

    public boolean finish(Outcome result) {
        if (!active()) return false;
        outcome = result;
        status = Status.SETTLED;
        if (result != Outcome.EXTRACTED) {
            zone = "";
            extractionTicks = requiredTicks = 0;
        }
        return true;
    }

    public void countKill() { if (active()) kills++; }

    public static RaidSession restore(UUID id, String map, Status status, Outcome outcome, String zone,
            int progress, int duration, long elapsed, int kills) {
        var session = new RaidSession(id, map);
        session.status = status;
        session.outcome = outcome;
        session.zone = zone == null ? "" : zone;
        session.requiredTicks = Math.max(0, duration);
        session.extractionTicks = Math.max(0, Math.min(progress, session.requiredTicks));
        session.elapsedTicks = Math.max(0, elapsed);
        session.kills = Math.max(0, kills);
        if (status == Status.SETTLED && outcome == null) session.outcome = Outcome.ABORTED;
        if (status != Status.SETTLED) session.resetExtraction();
        return session;
    }
}
