package dev.draginventory.client;

import dev.draginventory.WeaponSlots;

/** Time based HUD motion. Slot selection is committed before this starts. */
public final class WeaponSwitchAnimation {
    public static final long DURATION_MS = 240;
    public static final float DISTANCE = 18f;
    public static final float MAIN_DISTANCE = 24f;
    public static final float MAIN_DROP = 7f;
    public static final float MAIN_START_SCALE = 0.84f;
    private static final MainPose REST = new MainPose(0, 0, 1);
    private static long startedAt = Long.MIN_VALUE;
    private static int from = -1;
    private static int to = -1;

    private WeaponSwitchAnimation() {}

    public static void trigger(int previous, int next) {
        if (!WeaponSlots.isWeaponSlot(next) || previous == next) return;
        from = previous;
        to = next;
        startedAt = System.currentTimeMillis();
    }

    public static boolean active(long now) {
        return startedAt != Long.MIN_VALUE && now - startedAt < DURATION_MS;
    }

    public static float progress(long elapsedMs) {
        if (elapsedMs <= 0) return 0f;
        if (elapsedMs >= DURATION_MS) return 1f;
        float t = elapsedMs / (float) DURATION_MS;
        return t * t * (3f - 2f * t);
    }

    public static float offset(long now) {
        if (startedAt == Long.MIN_VALUE) return 0f;
        return offsetAt(now - startedAt, from, to);
    }

    public static float offsetAt(long elapsedMs, int previous, int next) {
        if (previous < 0 || next < 0 || previous == next) return 0f;
        float sign = next < previous ? 1f : -1f;
        return sign * DISTANCE * (1f - progress(elapsedMs));
    }

    public static MainPose mainPose(long now) {
        if (startedAt == Long.MIN_VALUE) return REST;
        return mainPoseAt(now - startedAt, from, to);
    }

    /** Incoming current-weapon panel; game selection is never gated by this pose. */
    public static MainPose mainPoseAt(long elapsedMs, int previous, int next) {
        if (previous < 0 || previous == next || !WeaponSlots.isWeaponSlot(next)) return REST;
        float remaining = 1f - progress(elapsedMs);
        float direction = next < previous ? 1f : -1f;
        return new MainPose(direction * MAIN_DISTANCE * remaining, MAIN_DROP * remaining,
                1f - (1f - MAIN_START_SCALE) * remaining);
    }

    public record MainPose(float x, float y, float scale) {}

    public static int from() { return from; }
    public static int to() { return to; }
}
