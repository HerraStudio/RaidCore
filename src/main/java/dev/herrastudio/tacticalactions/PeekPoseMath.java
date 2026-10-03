package dev.herrastudio.tacticalactions;

/** Original GD656Peek 0.1.1 upper-body pose, adapted to PAL Neo bone names (MIT). */
public final class PeekPoseMath {
    public static final float ANGLE_DEGREES = 15.0F;
    public static final float ANGLE_RADIANS = (float) Math.toRadians(ANGLE_DEGREES);

    private PeekPoseMath() {}

    /** Returns additive model-space units and radians; positive lean is left. */
    public static Delta delta(String bone, float lean) {
        return delta(bone, lean, lean);
    }

    /** Separate original tracks preserve GD656's offset-first return threshold and direction. */
    public static Delta delta(String bone, float angleNormalized, float offsetNormalized) {
        if (bone == null || !Float.isFinite(angleNormalized) || !Float.isFinite(offsetNormalized)) {
            return Delta.ZERO;
        }
        angleNormalized = Math.max(-1.0F, Math.min(1.0F, angleNormalized));
        offsetNormalized = Math.max(-1.0F, Math.min(1.0F, offsetNormalized));
        if (angleNormalized == 0.0F && offsetNormalized == 0.0F) return Delta.ZERO;
        float intensity = Math.max(Math.abs(offsetNormalized), Math.abs(angleNormalized));
        float direction = offsetNormalized != 0.0F ? -Math.signum(offsetNormalized)
                : Math.signum(angleNormalized);
        float angle = (float) Math.toRadians(ANGLE_DEGREES * angleNormalized);
        float sin = sampledSin(angle);
        float oneMinusCos = 1.0F - sampledCos(angle);
        return switch (bone) {
            case "torso" -> new Delta(
                    (sin * 6.4F * 1.08F + sin * 10.0F) * intensity,
                    (-oneMinusCos * 6.4F * 1.08F + 1.0F) * intensity,
                    0.0F,
                    -0.06F * intensity,
                    0.05F * direction * intensity,
                    angle * 1.08F);
            case "head" -> new Delta(
                    (sin * 12.3F * 1.11F + sin * 0.52F) * intensity,
                    (-oneMinusCos * 12.3F * 1.11F + 1.0F - 0.06F) * intensity,
                    0.0F,
                    -0.02F * intensity,
                    0.09F * direction * intensity,
                    angle * 1.10F);
            case "right_arm" -> new Delta(
                    (sin * 9.1F * 1.10F + sin * 1.5F - 0.18F) * intensity,
                    (-oneMinusCos * 9.1F * 1.10F - 0.03F - 0.04F) * intensity,
                    0.0F,
                    -0.10F * intensity,
                    0.06F * direction * intensity,
                    angle * 0.96F - Math.abs(angle) * 0.08F);
            case "left_arm" -> new Delta(
                    (sin * 9.1F * 1.10F + sin * 1.5F + 0.12F) * intensity,
                    (-oneMinusCos * 9.1F * 1.10F - 0.03F + 0.02F) * intensity,
                    0.0F,
                    0.04F * intensity,
                    0.02F * direction * intensity,
                    angle * 0.96F + Math.abs(angle) * 0.03F);
            default -> Delta.ZERO;
        };
    }

    private static float sampledSin(float radians) {
        int index = (int) (radians * 10430.378F) & 65535;
        return sineTableValue(index);
    }

    private static float sampledCos(float radians) {
        int index = (int) (radians * 10430.378F + 16384.0F) & 65535;
        return sineTableValue(index);
    }

    private static float sineTableValue(int index) {
        // The same sample as Mth's precomputed table without requiring Minecraft at test time.
        return (float) Math.sin(index * Math.PI * 2.0 / 65536.0);
    }

    public record Delta(float dx, float dy, float dz, float rx, float ry, float rz) {
        public static final Delta ZERO = new Delta(0, 0, 0, 0, 0, 0);
    }
}
