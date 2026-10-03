package dev.draginventory.client.wheel;

/** Direction-based picking in physical screen pixels, independent of wheel size. */
public final class WheelDirection {
    public static final double DEAD_ZONE_PIXELS = 15;

    private WheelDirection() {}

    /**
     * Preserve the current sector inside the central dead zone; otherwise pick
     * the sector shown in the pointer's direction. Rotation is in radians.
     */
    public static int select(int previous, double dxPixels, double dyPixels, double visualRotation) {
        int retained = previous >= 0 && previous < WheelGeometry.SECTORS ? previous : -1;
        if (!Double.isFinite(dxPixels) || !Double.isFinite(dyPixels)
                || !Double.isFinite(visualRotation)
                || Math.hypot(dxPixels, dyPixels) < DEAD_ZONE_PIXELS) return retained;

        // Normalize before inverse rotation so even very large finite screen
        // coordinates cannot overflow when the rotated components are added.
        double magnitude = Math.max(Math.abs(dxPixels), Math.abs(dyPixels));
        double dx = dxPixels / magnitude;
        double dy = dyPixels / magnitude;
        double cos = Math.cos(visualRotation);
        double sin = Math.sin(visualRotation);
        return WheelGeometry.sectorAt(dx * cos + dy * sin, dy * cos - dx * sin, 0);
    }
}
