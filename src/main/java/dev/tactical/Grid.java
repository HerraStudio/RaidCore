package dev.tactical;

import java.util.List;

/** Pure geometry; rig columns have independent 1x2 pouches and a bottom 1x1 row. */
public final class Grid {
    public record Rect(int x, int y, int w, int h) {
        public boolean overlaps(Rect other) {
            return x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y;
        }
    }
    public static boolean fits(int area, Rect rect, List<Rect> occupied) {
        int width = area == 0 ? 4 : 6, height = area == 0 ? 5 : 6;
        return fits(area,width,height,rect,occupied);
    }
    public static boolean fits(int area,int width,int height, Rect rect, List<Rect> occupied) {
        if (area < 0 || area > 1 || rect.w < 1 || rect.h < 1 || rect.x < 0 || rect.y < 0
                || rect.w > width || rect.h > height || rect.x > width - rect.w || rect.y > height - rect.h) return false;
        if (area == 0 && (rect.w != 1 || rect.y / 2 != (rect.y + rect.h - 1) / 2)) return false;
        return occupied.stream().noneMatch(rect::overlaps);
    }
}
