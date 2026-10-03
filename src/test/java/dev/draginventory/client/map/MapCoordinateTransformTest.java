package dev.draginventory.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MapCoordinateTransformTest {
    @Test
    void worldScreenRoundTripIsStable() {
        MapCoordinateTransform transform = new MapCoordinateTransform(120.5, -37.25, 2.5, 10, 20, 800, 500);
        double sx = transform.worldToScreenX(143.75);
        double sy = transform.worldToScreenY(-11.5);
        var world = transform.screenToWorld(sx, sy);
        assertEquals(143.75, world.x(), 1.0e-9);
        assertEquals(-11.5, world.z(), 1.0e-9);
    }

    @Test
    void centerMapsToViewportCenter() {
        MapCoordinateTransform transform = new MapCoordinateTransform(10, 20, 1, 40, 60, 600, 400);
        assertEquals(340, transform.worldToScreenX(10), 1.0e-9);
        assertEquals(260, transform.worldToScreenY(20), 1.0e-9);
        assertTrue(transform.contains(340, 260));
    }
}
