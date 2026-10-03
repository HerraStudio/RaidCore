package dev.draginventory.client;

/** Frame-rate independent, bounded motion in GWO panel units; no game or rendering dependencies. */
public final class WeaponHudMotion {
    public static final float INTENSITY = 2.5f;
    public static final float MAX_X = 7f;
    public static final float MAX_Y = 8f;
    public static final float MAX_ROLL = 1.5f;
    private static final double SHOT_RISE = 0.022;
    private static final double SHOT_RETURN = 0.18;
    private static final double SHOT_SETTLE = 0.10;
    private static final double COUNTER_KICK = 0.12;
    public static final Pose REST = new Pose(0, 0, 0);
    private boolean initialized;
    private float previousYaw, previousPitch;
    private boolean grounded;
    private final Spring lookX = new Spring(), lookY = new Spring(), lookRoll = new Spring();
    private double runWeight, stride, recoil;
    private double jumpAge = 10, jumpStrength;
    private double recoilAge = 10, recoilStart, recoilPeak;
    private int shotDirection = 1;

    public Pose step(float seconds, float yaw, float pitch, boolean sprinting, float speed,
                     boolean onGround, float verticalSpeed, int shots) {
        if (!Float.isFinite(seconds) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(speed) || !Float.isFinite(verticalSpeed) || seconds <= 0 || seconds > 0.25f) {
            reset();
            return REST;
        }
        if (!initialized) {
            initialized = true;
            previousYaw = yaw;
            previousPitch = pitch;
            grounded = onGround;
        }
        double yawRate = wrapDegrees(yaw - previousYaw) / seconds;
        double pitchRate = (pitch - previousPitch) / seconds;
        previousYaw = yaw;
        previousPitch = pitch;
        lookX.step(clamp(yawRate * 0.014, -2.2, 2.2), seconds);
        lookY.step(clamp(pitchRate * 0.012, -1.8, 1.8), seconds);
        lookRoll.step(clamp(-yawRate * 0.0018, -0.38, 0.38), seconds);

        boolean running = sprinting && onGround && speed > 0.5f;
        runWeight += ((running ? 1 : 0) - runWeight) * (1 - Math.exp(-seconds / 0.16));
        if (running || runWeight > 0.001) stride += seconds * 10.5 * clamp(speed / 5.6, 0.4, 1.4);
        else stride = 0;
        stride %= Math.PI * 2;
        double runX = Math.sin(stride) * 0.85 * runWeight;
        double runY = Math.cos(stride * 2) * 1.05 * runWeight;

        jumpAge += seconds;
        if (grounded && !onGround && verticalSpeed > 0.8f) {
            jumpAge = 0;
            jumpStrength = -1.10;
        } else if (!grounded && onGround) {
            jumpAge = 0;
            jumpStrength = 1.40;
        }
        grounded = onGround;
        double jumpY = jumpStrength * impact(jumpAge, 0.045, 0.23, 0.12);

        if (shots > 0) {
            recoilStart = recoil;
            recoilPeak = Math.min(1.8, Math.max(0, recoil) + Math.min(shots, 4) * 1.1);
            recoilAge = 0;
            shotDirection = -shotDirection;
        }
        recoilAge += seconds;
        recoil = recoilAge < SHOT_RISE ? recoilStart + (recoilPeak - recoilStart) * smooth(recoilAge / SHOT_RISE)
                : recoilPeak * impact(recoilAge, SHOT_RISE, SHOT_RETURN, SHOT_SETTLE);
        return new Pose(snap(clamp((lookX.value + runX + shotDirection * recoil * 0.22) * INTENSITY, -MAX_X, MAX_X)),
                snap(clamp((lookY.value + runY + jumpY - recoil) * INTENSITY, -MAX_Y, MAX_Y)),
                snap(clamp((lookRoll.value + Math.sin(stride) * runWeight * 0.22 + shotDirection * recoil * 0.12) * INTENSITY,
                        -MAX_ROLL, MAX_ROLL)));
    }

    public void clearRecoil() { recoil = recoilStart = recoilPeak = 0; recoilAge = 10; }

    public void reset() {
        initialized = false;
        lookX.reset();
        lookY.reset();
        lookRoll.reset();
        runWeight = stride = recoil = jumpStrength = 0;
        jumpAge = 10;
        clearRecoil();
        shotDirection = 1;
    }

    /** Fast impact, slower recoil recovery, then a small opposite rebound before coming to rest. */
    private static double impact(double age, double rise, double fall, double settle) {
        if (age < rise) return smooth(age / rise);
        if (age < rise + fall) return 1 - (1 + COUNTER_KICK) * smooth((age - rise) / fall);
        return -COUNTER_KICK * (1 - smooth((age - rise - fall) / settle));
    }

    private static double smooth(double progress) {
        double t = clamp(progress, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Exact critically damped spring solution: soft starts and stops without frame-dependent integration. */
    private static final class Spring {
        private double value, velocity;

        void step(double target, double seconds) {
            double omega = 22;
            double displacement = value - target;
            double coefficient = velocity + omega * displacement;
            double decay = Math.exp(-omega * seconds);
            value = target + (displacement + coefficient * seconds) * decay;
            velocity = (velocity - omega * coefficient * seconds) * decay;
        }

        void reset() { value = velocity = 0; }
    }

    private static double wrapDegrees(double value) {
        value = (value + 180) % 360;
        if (value < 0) value += 360;
        return value - 180;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static float snap(double value) { return Math.abs(value) < 0.001 ? 0 : (float) value; }

    public record Pose(float x, float y, float roll) {}
}
