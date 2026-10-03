package dev.draginventory.client.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class CubicBezierTest {
    @Test void evaluatesTimeOnTheXCoordinateInsteadOfTreatingItAsTheCurveParameter() {
        var curve = new CubicBezier(0.16, 1, 0.3, 1);
        // At parameter 0.3 the curve is at x=0.15426, y=0.657.
        assertEquals(0.657, curve.value(0.15426), 0.0000001);
        assertEquals(0, curve.value(-1));
        assertEquals(1, curve.value(2));
        assertEquals(0, curve.value(Double.NaN));
    }

    @Test void handlesFlatDerivativesAndLinearCurves() {
        var flat = new CubicBezier(0, 0, 0, 1);
        assertEquals(0.5, flat.value(0.125), 0.0000001);
        var linear = new CubicBezier(0, 0, 1, 1);
        for (double progress : new double[] {0, 0.01, 0.25, 0.5, 0.9, 1}) {
            assertEquals(progress, linear.value(progress), 0.0000001);
        }
    }

    @Test void validatesCssXControlPointsButAllowsIntentionalYOvershoot() {
        assertThrows(IllegalArgumentException.class, () -> new CubicBezier(-0.1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CubicBezier(0, 0, 1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CubicBezier(0, Double.NaN, 1, 1));
        assertEquals(1.25, new CubicBezier(0, 2, 1, 1).value(0.5), 0.0000001);
    }
}
