package dev.tactical.loot;

import dev.tactical.Grid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootPackingTest {
    @Test void mixedSizesUseTightCellsWithoutMovingExistingRectangles() {
        var occupied = new ArrayList<Grid.Rect>();
        var expected = List.of(
                new Grid.Rect(0, 0, 1, 1),
                new Grid.Rect(1, 0, 1, 3),
                new Grid.Rect(2, 0, 2, 3),
                new Grid.Rect(0, 3, 5, 2),
                new Grid.Rect(0, 5, 6, 6));
        for (Grid.Rect expectedRectangle : expected) {
            var before = List.copyOf(occupied);
            Grid.Rect rectangle = LootPacking.firstFree(expectedRectangle.w(), expectedRectangle.h(), occupied);
            assertEquals(expectedRectangle, rectangle);
            assertEquals(before, occupied);
            assertTrue(LootPacking.fits(326, rectangle, occupied));
            for (Grid.Rect existing : occupied) assertFalse(rectangle.overlaps(existing));
            occupied.add(rectangle);
        }
        assertEquals(11, LootPacking.rows(occupied));
        assertEquals(new Grid.Rect(4, 0, 1, 1), LootPacking.firstFree(1, 1, occupied));
        assertEquals(expected, occupied);
    }

    @Test void firstFitSearchesRowsBeforeColumnsAndFillsEarlierHoles() {
        var occupied = List.of(new Grid.Rect(0, 0, 4, 1), new Grid.Rect(0, 2, 6, 6));
        assertEquals(new Grid.Rect(4, 0, 2, 1), LootPacking.firstFree(2, 1, occupied));
        assertEquals(new Grid.Rect(0, 1, 3, 1), LootPacking.firstFree(3, 1, occupied));
        assertEquals(new Grid.Rect(0, 8, 2, 3), LootPacking.firstFree(2, 3, occupied));
        assertEquals(List.of(new Grid.Rect(0, 0, 4, 1), new Grid.Rect(0, 2, 6, 6)), occupied);
    }

    @Test void allSupportedDimensionsStartAtTheOriginInAnEmptyGrid() {
        for (int width = 1; width <= 6; width++) {
            for (int height = 1; height <= 6; height++) {
                assertEquals(new Grid.Rect(0, 0, width, height), LootPacking.firstFree(width, height, List.of()));
            }
        }
    }

    @Test void fiftyFourMaximumSizeStacksRetainEveryRectangle() {
        var occupied = new ArrayList<Grid.Rect>();
        for (int index = 0; index < 54; index++) {
            var before = List.copyOf(occupied);
            Grid.Rect rectangle = LootPacking.firstFree(6, 6, occupied);
            assertEquals(new Grid.Rect(0, index * 6, 6, 6), rectangle);
            assertEquals(before, occupied);
            for (Grid.Rect existing : occupied) assertFalse(rectangle.overlaps(existing));
            occupied.add(rectangle);
        }
        assertEquals(54, occupied.size());
        assertEquals(324, LootPacking.rows(occupied));
        assertEquals(new Grid.Rect(0, 324, 6, 2), LootPacking.firstFree(6, 2, occupied));
        assertEquals(new Grid.Rect(0,324,6,6),LootPacking.firstFree(6,6,occupied));
        for (int index = 0; index < occupied.size(); index++) {
            assertEquals(new Grid.Rect(0, index * 6, 6, 6), occupied.get(index));
        }
    }

    @Test void packingAppendsBeyondSearchWindowWithoutDroppingItems() {
        var occupied = new ArrayList<>(List.of(new Grid.Rect(0, 0, 6, 325)));
        assertEquals(new Grid.Rect(0, 325, 6, 1), LootPacking.firstFree(6, 1, occupied));
        occupied.add(new Grid.Rect(0, 325, 6, 1));
        var before = List.copyOf(occupied);
        assertEquals(new Grid.Rect(0,326,1,1),LootPacking.firstFree(1,1,occupied));
        assertEquals(before, occupied);
    }

    @Test void invalidPackingDimensionsAreRejected() {
        for (int dimension : new int[] {Integer.MIN_VALUE, -1, 0, 7, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> LootPacking.firstFree(dimension, 1, List.of()));
            assertThrows(IllegalArgumentException.class, () -> LootPacking.firstFree(1, dimension, List.of()));
        }
    }

    @Test void fitsChecksExactEdgesCollisionsAndArbitraryRowCounts() {
        var occupied = List.of(new Grid.Rect(0, 0, 2, 3));
        assertTrue(LootPacking.fits(8, new Grid.Rect(2, 0, 4, 8), occupied));
        assertTrue(LootPacking.fits(8, new Grid.Rect(0, 3, 2, 5), occupied));
        assertFalse(LootPacking.fits(8, new Grid.Rect(1, 2, 2, 2), occupied));
        assertFalse(LootPacking.fits(8, new Grid.Rect(5, 7, 2, 1), occupied));
        assertFalse(LootPacking.fits(8, new Grid.Rect(5, 7, 1, 2), occupied));
        assertTrue(LootPacking.fits(1, new Grid.Rect(0, 0, 6, 1), List.of()));
        assertTrue(LootPacking.fits(1000, new Grid.Rect(0, 999, 6, 1), List.of()));
    }

    @Test void fitsRejectsInvalidBoundsAndCandidateOverflow() {
        for (int rowCount : new int[] {Integer.MIN_VALUE, -1, 0}) {
            assertFalse(LootPacking.fits(rowCount, new Grid.Rect(0, 0, 1, 1), List.of()));
        }
        var invalid = List.of(
                new Grid.Rect(-1, 0, 1, 1),
                new Grid.Rect(0, -1, 1, 1),
                new Grid.Rect(0, 0, 0, 1),
                new Grid.Rect(0, 0, 1, 0),
                new Grid.Rect(0, 0, -1, 1),
                new Grid.Rect(0, 0, 1, -1),
                new Grid.Rect(0, 0, 7, 1),
                new Grid.Rect(Integer.MAX_VALUE, 0, 2, 1),
                new Grid.Rect(1, 0, Integer.MAX_VALUE, 1),
                new Grid.Rect(0, Integer.MAX_VALUE, 1, 1),
                new Grid.Rect(0, Integer.MAX_VALUE - 1, 1, 2),
                new Grid.Rect(0, 1, 1, Integer.MAX_VALUE));
        for (Grid.Rect rectangle : invalid) {
            assertFalse(LootPacking.fits(Integer.MAX_VALUE, rectangle, List.of()), rectangle.toString());
        }
        assertTrue(LootPacking.fits(Integer.MAX_VALUE,
                new Grid.Rect(0, Integer.MAX_VALUE - 1, 6, 1), List.of()));
    }

    @Test void occupiedOverflowCannotHideACollision() {
        assertFalse(LootPacking.fits(8, new Grid.Rect(5, 0, 1, 1),
                List.of(new Grid.Rect(1, 0, Integer.MAX_VALUE, 1))));
        assertFalse(LootPacking.fits(Integer.MAX_VALUE, new Grid.Rect(0, Integer.MAX_VALUE - 1, 1, 1),
                List.of(new Grid.Rect(0, Integer.MAX_VALUE - 2, 1, 6))));
        assertTrue(LootPacking.fits(8, new Grid.Rect(0, 0, 1, 1),
                List.of(new Grid.Rect(Integer.MAX_VALUE, 0, 6, 1),
                        new Grid.Rect(0, Integer.MAX_VALUE, 1, 6))));
        assertFalse(LootPacking.fits(8, new Grid.Rect(0, 0, 1, 1),
                List.of(new Grid.Rect(-1, -1, 2, 2))));
    }

    @Test void rowsFollowTheHighestBottomWithoutPadding() {
        assertEquals(6, LootPacking.COLUMNS);
        assertEquals(8, LootPacking.MIN_ROWS);
        assertEquals(8, LootPacking.rows(List.of()));
        assertEquals(8, LootPacking.rows(List.of(new Grid.Rect(0, 0, 6, 6))));
        assertEquals(8, LootPacking.rows(List.of(new Grid.Rect(0, 2, 6, 6))));
        assertEquals(9, LootPacking.rows(List.of(new Grid.Rect(0, 3, 6, 6))));
        var occupied = List.of(new Grid.Rect(0, 20, 1, 3), new Grid.Rect(1, 0, 1, 1));
        assertEquals(23, LootPacking.rows(occupied));
        assertEquals(new Grid.Rect(0, 20, 1, 3), occupied.get(0));
        assertEquals(Integer.MAX_VALUE, LootPacking.rows(List.of(new Grid.Rect(0, Integer.MAX_VALUE - 1, 1, 1))));
        assertThrows(ArithmeticException.class,
                () -> LootPacking.rows(List.of(new Grid.Rect(0, Integer.MAX_VALUE, 1, 1))));
    }

    @Test void orderSortsByRowThenColumnWithoutOverflowOrSizeTiebreakers() {
        var expected = List.of(
                new Grid.Rect(0, Integer.MIN_VALUE, 1, 1),
                new Grid.Rect(Integer.MIN_VALUE, 0, 1, 1),
                new Grid.Rect(4, 0, 1, 1),
                new Grid.Rect(Integer.MAX_VALUE, 0, 1, 1),
                new Grid.Rect(0, Integer.MAX_VALUE, 1, 1));
        var rectangles = new ArrayList<>(List.of(expected.get(4), expected.get(3), expected.get(1),
                expected.get(0), expected.get(2)));
        rectangles.sort(LootPacking.ORDER);
        assertEquals(expected, rectangles);
        assertEquals(0, LootPacking.ORDER.compare(new Grid.Rect(2, 3, 1, 1), new Grid.Rect(2, 3, 6, 6)));
    }
}
