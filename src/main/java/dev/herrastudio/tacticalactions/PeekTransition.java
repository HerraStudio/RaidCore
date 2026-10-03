package dev.herrastudio.tacticalactions;

/** GD656Peek's original angle and offset transitions, exposed as normalized values. */
public final class PeekTransition {
    private static final float ANGLE_DEGREES = 15.0F;
    private static final float OFFSET_BLOCKS = 0.3F;
    private static final float DURATION_SECONDS = 0.2F;
    private static final float TICK_SECONDS = 0.05F;
    private static final float EPSILON = 5.0E-4F;
    private float previousAngle;
    private float currentAngle;
    private float previousOffset;
    private float currentOffset;

    public void tick(float requestedTarget) {
        requestedTarget = Float.isFinite(requestedTarget)
                ? Math.max(-1.0F, Math.min(1.0F, requestedTarget)) : 0.0F;
        previousAngle = currentAngle;
        previousOffset = currentOffset;
        // Keep degrees internally so GD656's original 0.0005-degree thresholds match.
        currentAngle = approachByDuration(currentAngle, requestedTarget * ANGLE_DEGREES,
                DURATION_SECONDS);
        currentOffset = approachByDuration(currentOffset, requestedTarget * OFFSET_BLOCKS,
                DURATION_SECONDS);
    }

    public float get(float partialTick) {
        float partial = Float.isFinite(partialTick)
                ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        return (previousAngle + (currentAngle - previousAngle) * partial) / ANGLE_DEGREES;
    }

    public float current() {
        return currentAngle / ANGLE_DEGREES;
    }

    public float offset(float partialTick) {
        float partial = Float.isFinite(partialTick)
                ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        return (previousOffset + (currentOffset - previousOffset) * partial) / OFFSET_BLOCKS;
    }

    public float currentOffset() {
        return currentOffset / OFFSET_BLOCKS;
    }

    public void reset() {
        previousAngle = currentAngle = 0.0F;
        previousOffset = currentOffset = 0.0F;
    }

    private static int secondsToTicks(float seconds) {
        return seconds <= 0.0F ? 0 : Math.max(1, (int) Math.ceil(seconds / TICK_SECONDS));
    }

    private static float approachByDuration(float current, float target, float durationSeconds) {
        if (durationSeconds <= 0.0F) return target;
        int durationTicks = secondsToTicks(durationSeconds);
        if (durationTicks <= 0) return target;
        if (isPeekingOut(current, target)) {
            float absTarget = Math.abs(target);
            float fraction = absTarget <= EPSILON ? 1.0F
                    : Math.max(0.0F, Math.min(1.0F, Math.abs(current) / absTarget));
            float t = invEaseOutCubic(fraction);
            float tNext = Math.min(t + 1.0F / durationTicks, 1.0F);
            float nextFraction = easeOutCubic(tNext);
            float next = Math.copySign(absTarget * nextFraction, target);
            return Math.abs(next - target) < EPSILON ? target : next;
        }
        float referenceMagnitude = Math.max(Math.abs(current), Math.abs(target));
        if (referenceMagnitude < EPSILON) return target;
        float maxDelta = referenceMagnitude / durationTicks;
        return approachLinear(current, target, maxDelta);
    }

    private static boolean isPeekingOut(float current, float target) {
        if (Math.abs(target) <= EPSILON) return false;
        if (Math.abs(target) <= Math.abs(current) + EPSILON) return false;
        if (Math.abs(current) <= EPSILON) return true;
        return Math.signum(current) == Math.signum(target);
    }

    private static float easeOutCubic(float t) {
        return 1.0F - (float) Math.pow(1.0F - t, 3.0);
    }

    private static float invEaseOutCubic(float y) {
        return 1.0F - (float) Math.cbrt(1.0 - Math.max(0.0F, Math.min(1.0F, y)));
    }

    private static float approachLinear(float current, float target, float maxDelta) {
        return Math.abs(target - current) <= maxDelta ? target
                : current + Math.copySign(maxDelta, target - current);
    }
}
