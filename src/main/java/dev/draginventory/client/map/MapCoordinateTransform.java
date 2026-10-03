package dev.draginventory.client.map;

/**
 * North-up tactical-map transform. World +X maps right and world +Z maps down.
 * Zoom is pixels per block.
 */
public record MapCoordinateTransform(
        double centerX, double centerZ, double zoom,
        int viewportX, int viewportY, int viewportWidth, int viewportHeight) {

    public MapCoordinateTransform {
        if (!Double.isFinite(zoom) || zoom <= 0) throw new IllegalArgumentException("zoom must be positive");
        if (viewportWidth <= 0 || viewportHeight <= 0) throw new IllegalArgumentException("viewport must be positive");
    }

    public double worldToScreenX(double worldX) {
        return viewportX + viewportWidth * 0.5 + (worldX - centerX) * zoom;
    }

    public double worldToScreenY(double worldZ) {
        return viewportY + viewportHeight * 0.5 + (worldZ - centerZ) * zoom;
    }

    public Point screenToWorld(double screenX, double screenY) {
        return new Point(
                centerX + (screenX - (viewportX + viewportWidth * 0.5)) / zoom,
                centerZ + (screenY - (viewportY + viewportHeight * 0.5)) / zoom);
    }

    public boolean contains(double screenX, double screenY) {
        return screenX >= viewportX && screenX < viewportX + viewportWidth
                && screenY >= viewportY && screenY < viewportY + viewportHeight;
    }

    public record Point(double x, double z) {}
}
