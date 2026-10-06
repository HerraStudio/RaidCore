package dev.herrastudio.raidcore.ui.decal;

/** Anchors the entry hole while chipped surface extends along the incoming shot's tangent. */
public record ImpactProjection(double tangentU, double tangentV, double stretch) {
    public static final double MAX_STRETCH = 4.0;
    public static final ImpactProjection FRONTAL = new ImpactProjection(1, 0, 1);
    public static final int SEGMENTS = 12;
    private static final double HOLE_RADIUS = 0.42;

    /** Axis 0/1/2 is the face normal; U/V are the other two world axes in order. */
    public static ImpactProjection fromVelocity(double x, double y, double z, int normalAxis) {
        if (normalAxis < 0 || normalAxis > 2) throw new IllegalArgumentException("Invalid face axis");
        double magnitude = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (!Double.isFinite(magnitude) || magnitude == 0) return FRONTAL;
        x /= magnitude;
        y /= magnitude;
        z /= magnitude;
        double normal = normalAxis == 0 ? x : normalAxis == 1 ? y : z;
        double u = normalAxis == 0 ? y : x;
        double v = normalAxis == 2 ? y : z;
        double tangentLength = Math.hypot(u, v);
        if (tangentLength < 1.0e-8) return FRONTAL;
        double length = Math.sqrt(x * x + y * y + z * z);
        double aspect = Math.min(MAX_STRETCH, length / Math.max(Math.abs(normal), 1.0e-8));
        return new ImpactProjection(u / tangentLength, v / tangentLength, aspect);
    }

    /** The center stays fixed; outer damage follows the bullet's surface travel direction. */
    public double[] point(double u, double v, double halfSize) {
        if (halfSize <= 0) return new double[]{0, 0};
        double along = u * tangentU + v * tangentV;
        double across = -u * tangentV + v * tangentU;
        double severity = 1 - 1 / (stretch * stretch);
        double holeStretch = 1 + 0.25 * severity;
        double tail = Math.clamp((along / halfSize - HOLE_RADIUS) / (1 - HOLE_RADIUS), 0, 1);
        tail = tail * tail * (3 - 2 * tail);
        along = along * holeStretch + 2 * halfSize * (stretch - holeStretch) * tail;
        across *= 1 - 0.32 * severity * tail;
        return new double[]{along * tangentU - across * tangentV, along * tangentV + across * tangentU};
    }

    /** Nonlinear mapping needs a mesh: a single quad would drag the hole with the rim. */
    public Mesh mesh(double halfSize, double rotation, double minU, double maxU, double minV, double maxV) {
        int side = SEGMENTS + 1;
        double[] offsets = new double[side * side * 2];
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double fit = 1;
        for (int row = 0; row <= SEGMENTS; row++) for (int column = 0; column <= SEGMENTS; column++) {
            double s = halfSize * (2.0 * column / SEGMENTS - 1);
            double t = halfSize * (2.0 * row / SEGMENTS - 1);
            double[] point = point(s * cos - t * sin, s * sin + t * cos, halfSize);
            int index = (row * side + column) * 2;
            offsets[index] = point[0]; offsets[index + 1] = point[1];
            if (point[0] > 0) fit = Math.min(fit, maxU / point[0]);
            if (point[0] < 0) fit = Math.min(fit, minU / point[0]);
            if (point[1] > 0) fit = Math.min(fit, maxV / point[1]);
            if (point[1] < 0) fit = Math.min(fit, minV / point[1]);
        }
        fit = Math.clamp(fit, 0, 1);
        if (fit < 1) for (int i = 0; i < offsets.length; i++) offsets[i] *= fit;
        return new Mesh(offsets, fit);
    }

    public record Mesh(double[] offsets, double fit) {}
}
