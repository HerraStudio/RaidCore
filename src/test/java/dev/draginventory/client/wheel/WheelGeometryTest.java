package dev.draginventory.client.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WheelGeometryTest {
    @Test void mapsFiveClockwiseSectorCentersToUtilitySlots() {
        for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
            double angle = WheelGeometry.angle(sector);
            assertEquals(sector, WheelGeometry.sectorAt(Math.cos(angle) * 100, Math.sin(angle) * 100, 18));
            assertEquals(4 + sector, WheelGeometry.slot(sector));
        }
        assertEquals(-Math.PI / 2, WheelGeometry.angle(0), 0.0000001);
    }

    @Test void choosesTheAdjacentSectorOnEitherSideOfEveryBoundaryIncludingTopSeam() {
        double sectorAngle = Math.PI * 2 / WheelGeometry.SECTORS;
        for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
            double boundary = WheelGeometry.angle(sector) + sectorAngle / 2;
            assertEquals(sector, at(boundary - 0.00001));
            assertEquals((sector + 1) % WheelGeometry.SECTORS, at(boundary + 0.00001));
        }
    }

    @Test void deadCenterAndInvalidCoordinatesCannotSelectAHotbarSlot() {
        assertEquals(-1, WheelGeometry.sectorAt(0, 0, 0));
        assertEquals(-1, WheelGeometry.sectorAt(10, 0, 10));
        assertEquals(-1, WheelGeometry.sectorAt(5, 5, 10));
        assertEquals(-1, WheelGeometry.sectorAt(Double.NaN, 0, 10));
        assertEquals(-1, WheelGeometry.sectorAt(0, Double.POSITIVE_INFINITY, 10));
        assertEquals(-1, WheelGeometry.sectorAt(0, -100, -1));
        assertEquals(-1, WheelGeometry.slot(5));
        assertEquals(-1, WheelGeometry.slot(-1));
        assertTrue(Double.isNaN(WheelGeometry.angle(-1)));
    }

    @Test void selectionMarkerCrossesTheSeamAlongOneSectorInsteadOfFour() {
        double first = WheelGeometry.angle(0);
        double last = WheelGeometry.angle(4);
        double reverse = WheelGeometry.unwrapTarget(first, last);
        assertEquals(-Math.PI * 2 / 5, reverse - first, 0.0000001);
        assertEquals(Math.PI * 2 / 5, WheelGeometry.unwrapTarget(last, first) - last, 0.0000001);
        assertEquals(reverse, WheelGeometry.unwrapTarget(first, last + Math.PI * 20), 0.0000001);
    }

    @Test void scrollSelectionCyclesInBothDirectionsAcrossEverySector() {
        for (int sector = 0; sector < WheelGeometry.SECTORS; sector++) {
            assertEquals((sector + 1) % 5, WheelGeometry.nextSector(sector, 1));
            assertEquals((sector + 4) % 5, WheelGeometry.nextSector(sector, -1));
            assertEquals(sector, WheelGeometry.nextSector(sector, 5));
            assertEquals(sector, WheelGeometry.nextSector(sector, -10));
            assertEquals(4 + sector, WheelGeometry.slot(WheelGeometry.nextSector(sector, 0)));
        }
    }

    @Test void scrollFallbackAndLargeDeltasAlwaysYieldValidUtilitySlots() {
        assertEquals(1, WheelGeometry.nextSector(-1, 1));
        assertEquals(4, WheelGeometry.nextSector(5, -1));
        assertEquals(0, WheelGeometry.nextSector(Integer.MIN_VALUE, 0));
        assertEquals(1, WheelGeometry.nextSector(4, Integer.MAX_VALUE));
        assertEquals(1, WheelGeometry.nextSector(4, Integer.MIN_VALUE));
    }

    private static int at(double angle) {
        return WheelGeometry.sectorAt(Math.cos(angle) * 100, Math.sin(angle) * 100, 18);
    }
}
