package dev.tactical.loot;

import dev.tactical.Grid;
import java.util.Comparator;
import java.util.List;

public final class LootPacking {
    public static final int COLUMNS = 6;
    public static final int MIN_ROWS = 8;
    public static final Comparator<Grid.Rect> ORDER = Comparator.comparingInt(Grid.Rect::y)
            .thenComparingInt(Grid.Rect::x);
    private static final int MAX_ROWS = 326;

    private LootPacking() {
    }

    public static Grid.Rect firstFree(int width, int height, List<Grid.Rect> occupied) {
        if (width < 1 || width > COLUMNS || height < 1 || height > 6) {
            throw new IllegalArgumentException("Loot dimensions must be between 1 and 6");
        }
        for (int row = 0; row <= MAX_ROWS - height; row++) {
            for (int column = 0; column <= COLUMNS - width; column++) {
                Grid.Rect rectangle = new Grid.Rect(column, row, width, height);
                if (fits(MAX_ROWS, rectangle, occupied)) return rectangle;
            }
        }
        int bottom=rows(occupied);
        Math.addExact(bottom,height);
        return new Grid.Rect(0,bottom,width,height);
    }

    public static boolean fits(int rows, Grid.Rect rectangle, List<Grid.Rect> occupied) {
        if (!Grid.fits(1, COLUMNS, rows, rectangle, List.of())) return false;
        for (Grid.Rect existing : occupied) {
            int left = Math.max(0, existing.x());
            int top = Math.max(0, existing.y());
            long right = Math.min((long) COLUMNS, (long) existing.x() + existing.w());
            long bottom = Math.min((long) rows, (long) existing.y() + existing.h());
            if (right > left && bottom > top
                    && rectangle.overlaps(new Grid.Rect(left, top, (int) (right - left), (int) (bottom - top)))) {
                return false;
            }
        }
        return true;
    }

    public static int rows(List<Grid.Rect> occupied) {
        long result = MIN_ROWS;
        for (Grid.Rect rectangle : occupied) {
            result = Math.max(result, (long) rectangle.y() + rectangle.h());
        }
        return Math.toIntExact(result);
    }
}
