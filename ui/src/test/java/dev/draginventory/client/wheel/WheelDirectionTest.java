package dev.draginventory.client.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WheelDirectionTest {
    @Test void selectsEverySectorFarBeyondTheRenderedWheel() {
        for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
            assertEquals(sector, at(2, sector, 100_000, 0));
        }
    }

    @Test void deadZoneIsStrictlyLessThanFifteenPhysicalPixels() {
        assertEquals(3, WheelDirection.select(3, 0, -14.999, 0));
        assertEquals(0, WheelDirection.select(3, 0, -15, 0));
        assertEquals(0, WheelDirection.select(3, 0, -15.001, 0));
        assertEquals(1, WheelDirection.select(3, 15, 0, 0));
    }

    @Test void preservesEveryCurrentSectorAtTheCenterAndThroughoutTheDeadZone() {
        for (int previous = -1; previous < WheelGeometry.SECTORS; previous++) {
            assertEquals(previous, WheelDirection.select(previous, 0, 0, 0));
            for (int direction = 0; direction < WheelGeometry.SECTORS; direction++) {
                assertEquals(previous, at(previous, direction, 14.999, 0));
                assertEquals(previous, at(previous, direction, 14.999, Math.PI / 2));
            }
        }
    }

    @Test void mapsTheVisibleSectorsDuringNinetyDegreeSectorAndFullTurnSpins() {
        for (double rotation : new double[] { Math.PI / 2, Math.PI * 2 / 5,
                -Math.PI * 2 / 5, Math.PI * 2, Math.PI * 20 }) {
            for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
                assertEquals(sector, at(-1, sector, 100_000, rotation));
            }
        }
    }

    @Test void invalidInputsRetainSelectionInsteadOfCancellingIt() {
        for (int previous = -1; previous < WheelGeometry.SECTORS; previous++) {
            assertEquals(previous, WheelDirection.select(previous, Double.NaN, 100, 0));
            assertEquals(previous, WheelDirection.select(previous, 100, Double.NaN, 0));
            assertEquals(previous, WheelDirection.select(previous, Double.POSITIVE_INFINITY, 0, 0));
            assertEquals(previous, WheelDirection.select(previous, 0, Double.NEGATIVE_INFINITY, 0));
            assertEquals(previous, WheelDirection.select(previous, 100, 100, Double.NaN));
            assertEquals(previous, WheelDirection.select(previous, 100, 100, Double.POSITIVE_INFINITY));
        }
    }

    @Test void handlesHugeFiniteVectorsWithoutOverflowInRotation() {
        double huge = Double.MAX_VALUE;
        assertEquals(2, WheelDirection.select(-1, huge, huge, 0));
        assertEquals(0, WheelDirection.select(-1, huge, huge, Math.PI * 3 / 4));
        assertEquals(0, WheelDirection.select(-1, huge, -huge, Math.PI / 4));
        assertEquals(3, WheelDirection.select(-1, -huge, huge, 0));
    }

    @Test void invalidPreviousSelectionRemainsUnselectedInTheDeadZone() {
        assertEquals(-1, WheelDirection.select(-5, 0, 0, 0));
        assertEquals(-1, WheelDirection.select(5, 0, 0, 0));
        assertEquals(0, WheelDirection.select(5, 0, -100, 0));
    }

    private static int at(int previous, int sector, double distance, double rotation) {
        double angle = WheelGeometry.angle(sector) + rotation;
        return WheelDirection.select(previous,
                Math.cos(angle) * distance, Math.sin(angle) * distance, rotation);
    }
}
