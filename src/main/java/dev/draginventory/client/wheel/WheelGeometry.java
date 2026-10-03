package dev.draginventory.client.wheel;

/** Screen-space radial selection for the five utility hotbar slots. */
public final class WheelGeometry {
    public static final int SECTORS = 5;
    public static final int FIRST_SLOT = 4;
    private static final double FULL_TURN = Math.PI * 2;
    private static final double SECTOR_ANGLE = FULL_TURN / SECTORS;

    private WheelGeometry() {}

    /** Sector zero is centered above the origin; positive screen y points down. */
    public static int sectorAt(double dx, double dy, double deadRadius) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)
                || !Double.isFinite(deadRadius) || deadRadius < 0
                || Math.hypot(dx, dy) <= deadRadius) return -1;
        double clockwise = Math.atan2(dy, dx) + Math.PI / 2 + SECTOR_ANGLE / 2;
        clockwise %= FULL_TURN;
        if (clockwise < 0) clockwise += FULL_TURN;
        return Math.min(SECTORS - 1, (int) Math.floor(clockwise / SECTOR_ANGLE));
    }

    public static double angle(int sector) {
        return valid(sector) ? -Math.PI / 2 + sector * SECTOR_ANGLE : Double.NaN;
    }

    public static int slot(int sector) {
        return valid(sector) ? FIRST_SLOT + sector : -1;
    }

    /** Cycle logical selection without depending on the visual wheel rotation. */
    public static int nextSector(int currentSector, int steps) {
        int origin = valid(currentSector) ? currentSector : 0;
        return (int) Math.floorMod((long) origin + steps, SECTORS);
    }

    /** Keep rotations through the top seam on the shortest arc. */
    public static double unwrapTarget(double previousRadians, double targetRadians) {
        if (!Double.isFinite(previousRadians) || !Double.isFinite(targetRadians)) return previousRadians;
        double delta = Math.IEEEremainder(targetRadians - previousRadians, FULL_TURN);
        return previousRadians + delta;
    }

    private static boolean valid(int sector) {
        return sector >= 0 && sector < SECTORS;
    }
}
