package dev.draginventory.client.wheel;

/** A CSS cubic-bezier timing curve: elapsed time is its x coordinate. */
public final class CubicBezier {
    private final double x1;
    private final double y1;
    private final double x2;
    private final double y2;

    public CubicBezier(double x1, double y1, double x2, double y2) {
        if (!Double.isFinite(x1) || !Double.isFinite(y1)
                || !Double.isFinite(x2) || !Double.isFinite(y2)
                || x1 < 0 || x1 > 1 || x2 < 0 || x2 > 1) {
            throw new IllegalArgumentException("Finite control points and x coordinates in [0, 1] required");
        }
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public double value(double progress) {
        if (Double.isNaN(progress) || progress <= 0) return 0;
        if (progress >= 1) return 1;
        // Bisection also handles flat x derivatives, where Newton iteration is unstable.
        double lower = 0;
        double upper = 1;
        for (int iteration = 0; iteration < 40; iteration++) {
            double parameter = (lower + upper) / 2;
            if (coordinate(parameter, x1, x2) < progress) lower = parameter;
            else upper = parameter;
        }
        return coordinate((lower + upper) / 2, y1, y2);
    }

    private static double coordinate(double parameter, double first, double second) {
        double remaining = 1 - parameter;
        return 3 * remaining * remaining * parameter * first
                + 3 * remaining * parameter * parameter * second
                + parameter * parameter * parameter;
    }
}
