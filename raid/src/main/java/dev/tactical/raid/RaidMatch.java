package dev.tactical.raid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One server-owned round. Its clock advances once per server tick, independently of its roster. */
public final class RaidMatch {
    public enum Phase { WAITING, STARTING, IN_RAID, FINISHED }
    public record Participant(String spawnId, RaidSession session) { }

    public final UUID id;
    public final String map;
    public final String dimension;
    private final int durationSeconds;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private Phase phase = Phase.WAITING;
    private long elapsedTicks, startedAt, finishedAt;

    public RaidMatch(UUID id, String map, String dimension, int durationSeconds) {
        this.id = Objects.requireNonNull(id);
        this.map = Objects.requireNonNull(map);
        this.dimension = Objects.requireNonNull(dimension);
        int seconds = durationSeconds > 0 ? durationSeconds : RaidSession.DEFAULT_MATCH_DURATION_SECONDS;
        this.durationSeconds = Math.min(RaidSession.MAX_MATCH_DURATION_SECONDS, seconds);
    }

    public Phase phase() { return phase; }
    public int durationSeconds() { return durationSeconds; }
    public int durationTicks() { return durationSeconds * 20; }
    public long elapsedTicks() { return elapsedTicks; }
    public long remainingTicks() { return Math.max(0, durationTicks() - elapsedTicks); }
    public long startedAt() { return startedAt; }
    public long finishedAt() { return finishedAt; }
    public Map<UUID, Participant> participants() { return Collections.unmodifiableMap(participants); }
    public boolean contains(UUID player) { return participants.containsKey(player); }
    public int activePlayers() { return (int) participants.values().stream().filter(p -> p.session().inRaid()).count(); }

    public RaidSession join(UUID player, String spawnId) {
        if (phase != Phase.WAITING || participants.containsKey(player) || spawnId == null || spawnId.isBlank()
                || participants.values().stream().anyMatch(p -> p.spawnId().equals(spawnId))) return null;
        var session = RaidSession.waiting(id, map, durationSeconds);
        participants.put(player, new Participant(spawnId, session));
        return session;
    }

    public boolean leaveWaiting(UUID player) {
        return phase == Phase.WAITING && participants.remove(player) != null;
    }

    public boolean beginStart(int minimumPlayers) {
        if (phase != Phase.WAITING || participants.size() < Math.max(1, minimumPlayers)
                || participants.values().stream().anyMatch(p -> p.session().status() != RaidSession.Status.WAITING)) return false;
        phase = Phase.STARTING;
        return true;
    }

    public boolean completeStart() {
        if (phase != Phase.STARTING || participants.isEmpty()
                || participants.values().stream().anyMatch(p -> p.session().status() != RaidSession.Status.WAITING)) return false;
        participants.values().forEach(p -> p.session().start());
        startedAt = System.currentTimeMillis();
        phase = Phase.IN_RAID;
        return true;
    }

    public void cancelStart() { if (phase == Phase.STARTING) phase = Phase.WAITING; }

    /** Returns true only on the shared timeout transition. */
    public boolean tick() {
        if (phase != Phase.IN_RAID) return false;
        elapsedTicks++;
        if (remainingTicks() > 0) return false;
        participants.values().forEach(p -> p.session().finishAt(RaidSession.Outcome.TIMED_OUT, elapsedTicks));
        finishRound();
        return true;
    }

    /** A terminal player stays in the roster, even after acknowledging their own result. */
    public boolean finishParticipant(UUID player, RaidSession.Outcome outcome) {
        var participant = participants.get(player);
        if (participant == null || !participant.session().finishAt(outcome, elapsedTicks)) return false;
        reconcileFinished();
        return true;
    }

    public boolean reconcileFinished() {
        if (phase != Phase.IN_RAID && phase != Phase.STARTING) return false;
        if (participants.isEmpty() || participants.values().stream().anyMatch(p -> p.session().active())) return false;
        finishRound();
        return true;
    }

    public void abort() {
        if (phase == Phase.FINISHED) return;
        participants.values().forEach(p -> p.session().finishAt(RaidSession.Outcome.ABORTED, elapsedTicks));
        finishRound();
    }

    private void finishRound() {
        phase = Phase.FINISHED;
        finishedAt = System.currentTimeMillis();
    }

    public static RaidMatch restore(UUID id, String map, String dimension, int seconds, Phase phase,
            long elapsed, long startedAt, long finishedAt, Map<UUID, Participant> participants) {
        var match = new RaidMatch(id, map, dimension, seconds);
        for (var entry : participants.entrySet()) {
            var participant = entry.getValue();
            if (!participant.session().id.equals(id) || !participant.session().map.equals(map)
                    || participant.spawnId().isBlank()
                    || match.participants.values().stream().anyMatch(p -> p.spawnId().equals(participant.spawnId()))) {
                throw new IllegalArgumentException("Invalid shared Raid participant");
            }
            match.participants.put(entry.getKey(), participant);
        }
        match.phase = phase;
        match.elapsedTicks = Math.max(0, Math.min(match.durationTicks(), elapsed));
        match.startedAt = Math.max(0, startedAt);
        match.finishedAt = Math.max(0, finishedAt);
        return match;
    }
}
