package dev.tactical.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WholeItemDragTest {
    private static final double EPSILON = 1.0e-9;
    private static final int CELL_SIZE = 28;

    @Test void everyGrabbedCellOfFiveByTwoItemSharesItsWholeRoot() {
        double anchorX = 408.125;
        double anchorY = 283.625;
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 5; column++) {
                double mouseX = anchorX + column * CELL_SIZE + 11.375;
                double mouseY = anchorY + row * CELL_SIZE + 19.875;
                WholeItemDrag drag = WholeItemDrag.capture(mouseX, mouseY, anchorX, anchorY, 140, 56);

                assertPoint(anchorX, anchorY, drag.root(mouseX, mouseY, false));
                assertEquals(column * CELL_SIZE + 11.375, drag.offsetX(), EPSILON);
                assertEquals(row * CELL_SIZE + 19.875, drag.offsetY(), EPSILON);
                assertEquals(new WholeItemDrag.Cell(2, 3),
                        drag.cell(mouseX, mouseY, anchorX - 2 * CELL_SIZE,
                                anchorY - 3 * CELL_SIZE, CELL_SIZE, false));
            }
        }
    }

    @Test void translatingPointerByWholeCellsTranslatesTheCompleteFootprint() {
        double gridX = 352.125;
        double gridY = 97.375;
        double anchorX = gridX + 2 * CELL_SIZE;
        double anchorY = gridY + CELL_SIZE;
        double mouseX = anchorX + 4 * CELL_SIZE + 17.25;
        double mouseY = anchorY + CELL_SIZE + 7.75;
        WholeItemDrag drag = WholeItemDrag.capture(mouseX, mouseY, anchorX, anchorY, 140, 56);

        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                double translatedX = mouseX + dx * CELL_SIZE;
                double translatedY = mouseY + dy * CELL_SIZE;
                assertPoint(anchorX + dx * CELL_SIZE, anchorY + dy * CELL_SIZE,
                        drag.root(translatedX, translatedY, false));
                assertEquals(new WholeItemDrag.Cell(2 + dx, 1 + dy),
                        drag.cell(translatedX, translatedY, gridX, gridY, CELL_SIZE, false));
            }
        }
    }

    @Test void previewSnapsTheFloatingRootInsteadOfThePointerCell() {
        double gridX = 352;
        double gridY = 226;
        WholeItemDrag drag = WholeItemDrag.capture(496.25, 262.5, 360, 231, 140, 56);
        double targetRootX = gridX + 2 * CELL_SIZE + 0.49 * CELL_SIZE;
        double targetRootY = gridY + 3 * CELL_SIZE - 0.49 * CELL_SIZE;
        double mouseX = targetRootX + drag.offsetX();
        double mouseY = targetRootY + drag.offsetY();

        assertPoint(targetRootX, targetRootY, drag.root(mouseX, mouseY, false));
        assertEquals(new WholeItemDrag.Cell(2, 3),
                drag.cell(mouseX, mouseY, gridX, gridY, CELL_SIZE, false));
        assertNotEquals(new WholeItemDrag.Cell((int) ((mouseX - gridX) / CELL_SIZE),
                (int) ((mouseY - gridY) / CELL_SIZE)),
                drag.cell(mouseX, mouseY, gridX, gridY, CELL_SIZE, false));
        assertEquals(new WholeItemDrag.Cell(3, 2),
                drag.cell(mouseX + 0.02 * CELL_SIZE, mouseY - 0.02 * CELL_SIZE,
                        gridX, gridY, CELL_SIZE, false));
    }

    @Test void rotatingFiveByTwoPreservesTheRelativeGrabPointOnTwoByFive() {
        WholeItemDrag drag = WholeItemDrag.capture(512.5, 254.5, 400, 217, 140, 56);
        double mouseX = 617.125;
        double mouseY = 384.875;
        WholeItemDrag.Point root = drag.root(mouseX, mouseY, true);

        assertEquals(drag.offsetX() / 140, (mouseX - root.x()) / 56, EPSILON);
        assertEquals(drag.offsetY() / 56, (mouseY - root.y()) / 140, EPSILON);
        assertPoint(mouseX - 45, mouseY - 93.75, root);
        assertEquals(new WholeItemDrag.Cell(3, 4),
                drag.cell(mouseX, mouseY, root.x() - 3 * CELL_SIZE,
                        root.y() - 4 * CELL_SIZE, CELL_SIZE, true));
    }

    @Test void capturingAnAlreadyRotatedTwoByFiveUsesItsActualInitialDimensions() {
        double anchorX = 420.375;
        double anchorY = 186.625;
        double mouseX = anchorX + 42.125;
        double mouseY = anchorY + 127.875;
        WholeItemDrag drag = WholeItemDrag.capture(mouseX, mouseY, anchorX, anchorY, 56, 140);

        assertPoint(anchorX, anchorY, drag.root(mouseX, mouseY, false));
        WholeItemDrag.Point rotated = drag.root(mouseX, mouseY, true);
        assertEquals(42.125 / 56, (mouseX - rotated.x()) / 140, EPSILON);
        assertEquals(127.875 / 140, (mouseY - rotated.y()) / 56, EPSILON);
        assertPoint(anchorX, anchorY, drag.root(mouseX, mouseY, false));
    }

    @Test void rootsOutsideTheGridRemainOutsideForTheWholeFootprintValidation() {
        double gridX = 352;
        double gridY = 300;
        WholeItemDrag drag = WholeItemDrag.capture(490.75, 345.25, gridX, gridY, 140, 56);

        assertEquals(new WholeItemDrag.Cell(-2, -1),
                drag.cell(gridX - 2 * CELL_SIZE + drag.offsetX(),
                        gridY - CELL_SIZE + drag.offsetY(), gridX, gridY, CELL_SIZE, false));
        assertEquals(new WholeItemDrag.Cell(6, 12),
                drag.cell(gridX + 6 * CELL_SIZE + drag.offsetX(),
                        gridY + 12 * CELL_SIZE + drag.offsetY(), gridX, gridY, CELL_SIZE, false));
    }

    @Test void differentContainerOriginsAndScrollOffsetsDoNotChangeTheGrabPoint() {
        double sourceX = 352;
        double sourceY = 300 - 74.625;
        double targetX = 584;
        double targetY = 64 - 44.125;
        double anchorX = sourceX + CELL_SIZE;
        double anchorY = sourceY + 2 * CELL_SIZE;
        WholeItemDrag drag = WholeItemDrag.capture(anchorX + 123.5, anchorY + 47.25,
                anchorX, anchorY, 140, 56);
        double targetRootX = targetX + 2 * CELL_SIZE;
        double targetRootY = targetY + 3 * CELL_SIZE;
        double mouseX = targetRootX + drag.offsetX();
        double mouseY = targetRootY + drag.offsetY();

        assertPoint(targetRootX, targetRootY, drag.root(mouseX, mouseY, false));
        assertEquals(new WholeItemDrag.Cell(2, 3),
                drag.cell(mouseX, mouseY, targetX, targetY, CELL_SIZE, false));
        assertEquals(new WholeItemDrag.Cell(2, 1),
                drag.cell(mouseX, mouseY, targetX, 64, CELL_SIZE, false));
    }

    @Test void arbitraryFractionalPointerMovementHasNoAccumulatedRoundingOrDrift() {
        double anchorX = 403.317;
        double anchorY = 267.981;
        double startX = anchorX + 117.437;
        double startY = anchorY + 48.619;
        WholeItemDrag drag = WholeItemDrag.capture(startX, startY, anchorX, anchorY, 140, 56);

        for (int i = 0; i < 200; i++) {
            double dx = Math.sin(i * 0.37) * 93.257;
            double dy = Math.cos(i * 0.23) * 47.993;
            WholeItemDrag.Point root = drag.root(startX + dx, startY + dy, false);
            assertPoint(anchorX + dx, anchorY + dy, root);
            assertEquals(new WholeItemDrag.Cell((int) Math.round(dx / CELL_SIZE),
                    (int) Math.round(dy / CELL_SIZE)),
                    drag.cell(startX + dx, startY + dy, anchorX, anchorY, CELL_SIZE, false));
        }
        assertPoint(anchorX, anchorY, drag.root(startX, startY, false));
    }

    @Test void invalidFootprintAndCellDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WholeItemDrag.capture(1, 2, 0, 0, 0, 56));
        assertThrows(IllegalArgumentException.class, () -> WholeItemDrag.capture(1, 2, 0, 0, 140, -1));
        WholeItemDrag drag = WholeItemDrag.capture(1, 2, 0, 0, 140, 56);
        assertThrows(IllegalArgumentException.class, () -> drag.cell(1, 2, 0, 0, 0, false));
    }

    private static void assertPoint(double x, double y, WholeItemDrag.Point point) {
        assertEquals(x, point.x(), EPSILON);
        assertEquals(y, point.y(), EPSILON);
    }
}
