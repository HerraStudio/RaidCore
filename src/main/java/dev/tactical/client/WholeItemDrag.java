package dev.tactical.client;

/** Keeps the grab point relative to the complete item footprint. All positions share one coordinate space. */
public record WholeItemDrag(double offsetX, double offsetY, int width, int height) {
    public WholeItemDrag {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Item dimensions must be positive");
        }
    }

    public record Point(double x, double y) {}
    public record Cell(int x, int y) {}

    public static WholeItemDrag capture(double mouseX, double mouseY, double anchorX, double anchorY,
                                        int width, int height) {
        return new WholeItemDrag(mouseX - anchorX, mouseY - anchorY, width, height);
    }

    /** Rotation preserves the grab point's fraction of the complete width and height. */
    public Point root(double mouseX, double mouseY, boolean swapped) {
        double currentOffsetX = swapped ? offsetX / width * height : offsetX;
        double currentOffsetY = swapped ? offsetY / height * width : offsetY;
        return new Point(mouseX - currentOffsetX, mouseY - currentOffsetY);
    }

    /** Snap the item root, without moving an out-of-bounds footprint back into the grid. */
    public Cell cell(double mouseX, double mouseY, double gridX, double gridY, int cellSize, boolean swapped) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("Cell size must be positive");
        }
        Point root = root(mouseX, mouseY, swapped);
        return new Cell((int) Math.round((root.x() - gridX) / cellSize),
                (int) Math.round((root.y() - gridY) / cellSize));
    }
}
