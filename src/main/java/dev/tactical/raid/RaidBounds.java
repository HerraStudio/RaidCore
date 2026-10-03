package dev.tactical.raid;

/** Inclusive, finite world-space extraction box. The horizontal area is square. */
public record RaidBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public RaidBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid extraction bounds");
        }
    }

    public static RaidBounds square(double x, double y, double z, double radius) {
        if (!Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("Invalid radius");
        return new RaidBounds(x - radius, y - 1, z - radius, x + radius, y + 4, z + radius);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
