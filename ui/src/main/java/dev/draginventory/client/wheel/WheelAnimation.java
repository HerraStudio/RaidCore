package dev.draginventory.client.wheel;

/** Absolute-time wheel motion; retargeting always starts at the current visual pose. */
public final class WheelAnimation {
    public static final long OPEN_MS = 180;
    public static final long CLOSE_MS = 150;
    public static final long HOVER_MS = 150;
    public static final long SPIN_MS = 180;
    private static final long PULSE_RISE_MS = 55;
    private static final long PULSE_FALL_MS = 125;
    private static final float CLOSED_SCALE = 0.86f;
    private static final CubicBezier ENTER = new CubicBezier(0.16, 1, 0.3, 1);
    private static final CubicBezier EXIT = new CubicBezier(0.4, 0, 1, 1);
    private static final CubicBezier SELECT = new CubicBezier(0.22, 1, 0.36, 1);
    private final Tween alpha = new Tween(0);
    private final Tween scale = new Tween(CLOSED_SCALE);
    private final Tween rotation = new Tween(0);
    private final Tween angle = new Tween(WheelGeometry.angle(0));
    private final Tween[] highlights = new Tween[WheelGeometry.SECTORS];
    private boolean open;
    private boolean closing;
    private boolean markerInitialized;
    private int hovered = -1;
    private long closedAt;
    private long pulseAt;
    private float pulseFrom;
    private boolean pulseActive;
    private double rotationTarget;

    public WheelAnimation() {
        for (int sector = 0; sector < highlights.length; sector++) highlights[sector] = new Tween(0);
    }

    public void open(long nowMillis) {
        if (open) return;
        boolean wasVisible = isVisible(nowMillis);
        if (!wasVisible) reset();
        open = true;
        closing = false;
        alpha.target(1, nowMillis, OPEN_MS, ENTER);
        scale.target(1, nowMillis, OPEN_MS, ENTER);
    }

    public void close(long nowMillis) {
        if (!open) return;
        open = false;
        closing = true;
        closedAt = nowMillis;
        alpha.target(0, nowMillis, CLOSE_MS, EXIT);
        scale.target(CLOSED_SCALE, nowMillis, CLOSE_MS, EXIT);
        // Closing only fades/scales: do not introduce or finish a spin after dismissal.
        rotationTarget = rotation.value(nowMillis);
        rotation.set(rotationTarget);
    }

    /** One clockwise sector per positive logical scroll step, independent of selection. */
    public void spin(int steps, long nowMillis) {
        if (!open || steps == 0) return;
        // Retarget from the visible pose while retaining every pending scroll step.
        rotationTarget += steps * (Math.PI * 2 / WheelGeometry.SECTORS);
        rotation.target(rotationTarget, nowMillis, SPIN_MS, SELECT);
    }

    public void hover(int sector, long nowMillis) {
        if (!open) return;
        if (sector < 0 || sector >= WheelGeometry.SECTORS) sector = -1;
        if (hovered == sector) return;
        hovered = sector;
        for (int index = 0; index < highlights.length; index++) {
            highlights[index].target(index == sector ? 1 : 0, nowMillis, HOVER_MS, SELECT);
        }
        if (sector >= 0) {
            double destination = WheelGeometry.angle(sector);
            if (!markerInitialized) {
                angle.set(destination);
                markerInitialized = true;
            } else {
                angle.target(WheelGeometry.unwrapTarget(angle.value(nowMillis), destination),
                        nowMillis, HOVER_MS, SELECT);
            }
            pulseFrom = pulse(nowMillis);
            pulseAt = nowMillis;
            pulseActive = true;
        }
    }

    public void reset() {
        open = false;
        closing = false;
        markerInitialized = false;
        hovered = -1;
        pulseActive = false;
        pulseFrom = 0;
        alpha.set(0);
        scale.set(CLOSED_SCALE);
        rotationTarget = 0;
        rotation.set(0);
        angle.set(WheelGeometry.angle(0));
        for (Tween highlight : highlights) highlight.set(0);
    }

    public boolean isVisible(long nowMillis) {
        if (closing && elapsed(nowMillis, closedAt) >= CLOSE_MS) closing = false;
        return open || closing;
    }

    public boolean isClosing() { return closing; }
    public boolean isOpen() { return open; }
    public int hovered() { return hovered; }

    public Frame frame(long nowMillis) {
        isVisible(nowMillis);
        float[] values = new float[highlights.length];
        for (int sector = 0; sector < values.length; sector++) values[sector] = (float) highlights[sector].value(nowMillis);
        return new Frame((float) alpha.value(nowMillis), (float) scale.value(nowMillis),
                (float) rotation.value(nowMillis), values, (float) angle.value(nowMillis), pulse(nowMillis));
    }

    private float pulse(long nowMillis) {
        if (!pulseActive) return 0;
        double elapsed = elapsed(nowMillis, pulseAt);
        if (elapsed < PULSE_RISE_MS) {
            return (float) (pulseFrom + (1 - pulseFrom) * SELECT.value(elapsed / PULSE_RISE_MS));
        }
        return (float) (1 - ENTER.value((elapsed - PULSE_RISE_MS) / PULSE_FALL_MS));
    }

    private static double elapsed(long nowMillis, long startedAt) {
        return Math.max(0, (double) nowMillis - startedAt);
    }

    /** Angles use radians; snapshots cannot mutate the animator. */
    public record Frame(float alpha, float scale, float rotation, float[] highlights,
                        float highlightAngle, float selectionPulse) {
        public Frame {
            highlights = highlights.clone();
        }

        @Override
        public float[] highlights() { return highlights.clone(); }
    }

    private static final class Tween {
        private double from;
        private double to;
        private long startedAt;
        private long duration;
        private CubicBezier easing = ENTER;

        private Tween(double value) { set(value); }

        private void set(double value) {
            from = to = value;
            duration = 0;
        }

        private void target(double target, long nowMillis, long durationMillis, CubicBezier curve) {
            from = value(nowMillis);
            to = target;
            startedAt = nowMillis;
            duration = durationMillis;
            easing = curve;
        }

        private double value(long nowMillis) {
            if (duration == 0) return to;
            double progress = elapsed(nowMillis, startedAt) / duration;
            return from + (to - from) * easing.value(progress);
        }
    }
}
