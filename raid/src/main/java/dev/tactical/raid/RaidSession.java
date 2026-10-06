package dev.tactical.raid;

import java.util.UUID;

/** One participant's server-owned state. Shared rounds use RaidMatch for the round clock. */
public final class RaidSession {
    public static final int DEFAULT_MATCH_DURATION_SECONDS = 30 * 60;
    public static final int MAX_MATCH_DURATION_SECONDS = 24 * 60 * 60;
    public enum Status { WAITING, ACTIVE, EXTRACTING, SETTLED }
    public enum Outcome { EXTRACTED, DEAD, ABORTED, TIMED_OUT }

    public final UUID id;
    public final String map;
    private final int matchDurationTicks;
    private Status status = Status.ACTIVE;
    private Outcome outcome;
    private String zone = "";
    private int extractionTicks, requiredTicks, kills;
    private long elapsedTicks;

    public RaidSession(UUID id, String map) {
        this(id, map, DEFAULT_MATCH_DURATION_SECONDS);
    }

    public RaidSession(UUID id, String map, int matchDurationSeconds) {
        this.id = id;
        this.map = map;
        int seconds = matchDurationSeconds > 0 ? matchDurationSeconds : DEFAULT_MATCH_DURATION_SECONDS;
        this.matchDurationTicks = Math.min(MAX_MATCH_DURATION_SECONDS, seconds) * 20;
    }

    public Status status() { return status; }
    public Outcome outcome() { return outcome; }
    public String zone() { return zone; }
    public int requiredTicks() { return requiredTicks; }
    public int remainingTicks() { return Math.max(0, requiredTicks - extractionTicks); }
    public int extractionTicks() { return extractionTicks; }
    public long elapsedTicks() { return elapsedTicks; }
    public int matchDurationTicks() { return matchDurationTicks; }
    public long remainingMatchTicks() { return Math.max(0, matchDurationTicks - elapsedTicks); }
    public int kills() { return kills; }
    public boolean active() { return status != Status.SETTLED; }
    public boolean inRaid() { return status == Status.ACTIVE || status == Status.EXTRACTING; }

    public static RaidSession waiting(UUID match, String map, int seconds) {
        var session = new RaidSession(match, map, seconds);
        session.status = Status.WAITING;
        return session;
    }

    public boolean start() {
        if (status != Status.WAITING) return false;
        status = Status.ACTIVE;
        return true;
    }

    /** A new zone starts a fresh countdown; leaving the area always discards progress. */
    public boolean tick(String nextZone, int durationTicks) {
        if (!inRaid()) return false;
        elapsedTicks++;
        // Expiry takes precedence over an extraction that would complete on the deadline.
        if (remainingMatchTicks() == 0) {
            finish(Outcome.TIMED_OUT);
            return false;
        }
        return advanceExtraction(nextZone, durationTicks);
    }

    /** Advances personal extraction only; the shared match owns timeout and round elapsed time. */
    public boolean tickInMatch(String nextZone, int durationTicks) {
        if (!inRaid()) return false;
        elapsedTicks++;
        return advanceExtraction(nextZone, durationTicks);
    }

    public boolean tickInMatch(String nextZone, int durationTicks, long sharedElapsedTicks) {
        if (!inRaid()) return false;
        elapsedTicks = Math.max(0, sharedElapsedTicks);
        return advanceExtraction(nextZone, durationTicks);
    }

    public boolean finishAt(Outcome result, long sharedElapsedTicks) {
        if (inRaid()) elapsedTicks = Math.max(0, sharedElapsedTicks);
        return finish(result);
    }

    private boolean advanceExtraction(String nextZone, int durationTicks) {
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
        if (!inRaid()) return;
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

    public void countKill() { if (inRaid()) kills++; }

    public static RaidSession restore(UUID id, String map, Status status, Outcome outcome, String zone,
            int progress, int duration, long elapsed, int kills) {
        return restore(id, map, status, outcome, zone, progress, duration, elapsed, kills,
                DEFAULT_MATCH_DURATION_SECONDS);
    }

    public static RaidSession restore(UUID id, String map, Status status, Outcome outcome, String zone,
            int progress, int duration, long elapsed, int kills, int matchDurationSeconds) {
        var session = new RaidSession(id, map, matchDurationSeconds);
        session.status = status;
        session.outcome = outcome;
        session.zone = zone == null ? "" : zone;
        session.requiredTicks = Math.max(0, duration);
        session.extractionTicks = Math.max(0, Math.min(progress, session.requiredTicks));
        session.elapsedTicks = Math.max(0, elapsed);
        session.kills = Math.max(0, kills);
        if (status == Status.SETTLED && outcome == null) session.outcome = Outcome.ABORTED;
        if (status == Status.WAITING) {
            session.outcome = null;
            session.zone = "";
            session.extractionTicks = session.requiredTicks = 0;
            session.elapsedTicks = 0;
        } else if (status != Status.SETTLED) session.resetExtraction();
        return session;
    }
}
