package dev.herrastudio.raidcore.ui.decal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ImpactProjectionTest {
    private static final double HALF = 0.072;

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void frontalShotsKeepTheOriginalFootprintOnEitherSideOfEachAxis(int axis) {
        for (int sign : new int[]{-1, 1}) {
            double[] velocity = new double[3]; velocity[axis] = sign;
            var p = ImpactProjection.fromVelocity(velocity[0], velocity[1], velocity[2], axis);
            assertEquals(1, p.stretch());
            assertArrayEquals(new double[]{HALF * 0.6, -HALF * 0.3}, p.point(HALF * 0.6, -HALF * 0.3, HALF), 1.0e-12);
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void angleControlsDamageSpanWhileTheHoleStaysAtTheImpact(int axis) {
        for (int angle : new int[]{30, 45, 60, 75, 89}) {
            double[] velocity = direction(axis, angle, 0.61);
            var p = ImpactProjection.fromVelocity(velocity[0], velocity[1], velocity[2], axis);
            assertEquals(Math.min(4, 1 / Math.cos(Math.toRadians(angle))), p.stretch(), 1.0e-10);
            assertArrayEquals(new double[]{0, 0}, p.point(0, 0, HALF), 1.0e-12);
            double[] rear = p.point(-HALF * p.tangentU(), -HALF * p.tangentV(), HALF);
            double[] front = p.point(HALF * p.tangentU(), HALF * p.tangentV(), HALF);
            assertEquals(2 * HALF * p.stretch(), along(front, p) - along(rear, p), 1.0e-12);
            assertTrue(along(front, p) + along(rear, p) > 0, "Damage must extend along the incoming bullet's surface tangent");
        }
    }

    @Test void theDarkHoleCoreDoesNotStretchWithTheLongOuterDamage() {
        var p = ImpactProjection.fromVelocity(1, -0.02, 0, 1);
        double[] rear = p.point(-HALF * 0.2, HALF * 0.1, HALF), front = p.point(HALF * 0.2, HALF * 0.1, HALF);
        assertEquals(-rear[0], front[0], 1.0e-12);
        assertTrue(front[0] / (HALF * 0.2) < 1.25);
        assertEquals(HALF * 0.1, rear[1], 1.0e-12);
        assertTrue(p.point(HALF, 0, HALF)[0] > 6 * HALF);
    }

    @Test void randomTextureRotationCannotMoveTheAnchorOrRotateTheTailAwayFromTheShot() {
        var p = ImpactProjection.fromVelocity(3, -1, 4, 1);
        for (int i = 0; i < 72; i++) {
            var mesh = mesh(p, i * Math.PI / 36);
            int center = mesh.offsets().length / 2 - 1;
            assertEquals(0, mesh.offsets()[center], 1.0e-12);
            assertEquals(0, mesh.offsets()[center + 1], 1.0e-12);
            double centroid = 0;
            for (int j = 0; j < mesh.offsets().length; j += 2) centroid += mesh.offsets()[j] * p.tangentU() + mesh.offsets()[j + 1] * p.tangentV();
            assertTrue(centroid > 0);
        }
    }

    @Test void grazingAndExactlyParallelDirectionsRemainFiniteAndBounded() {
        for (double normal : new double[]{0, 1.0e-100, -1.0e-100}) {
            var p = ImpactProjection.fromVelocity(1, normal, 0, 1);
            assertEquals(4, p.stretch());
            for (double coordinate : mesh(p, 0).offsets()) assertTrue(Double.isFinite(coordinate));
        }
    }

    @Test void invalidOrMissingVelocityFallsBackToTheOriginalFootprint() {
        for (double invalid : new double[]{0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(ImpactProjection.FRONTAL, ImpactProjection.fromVelocity(invalid, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ImpactProjection.fromVelocity(1, 1, 1, 3));
    }

    @Test void speedDoesNotAffectShapeEvenForExtremeFiniteValues() {
        var expected = ImpactProjection.fromVelocity(3, -1, 4, 1);
        for (double speed : new double[]{1.0e-300, 1.0e-9, 20, 1.0e300}) {
            var actual = ImpactProjection.fromVelocity(3 * speed, -speed, 4 * speed, 1);
            assertEquals(expected.tangentU(), actual.tangentU(), 1.0e-12);
            assertEquals(expected.tangentV(), actual.tangentV(), 1.0e-12);
            assertEquals(expected.stretch(), actual.stretch(), 1.0e-12);
        }
    }

    @Test void allMeshVerticesFitThinFacesAndEdgesWithoutMovingTheHole() {
        var p = ImpactProjection.fromVelocity(5, -0.1, 3, 1);
        for (double rotation : new double[]{0, 0.73, 2.2}) {
            var mesh = p.mesh(HALF, rotation, -0.032, 0.13, -0.18, 0.03);
            for (int i = 0; i < mesh.offsets().length; i += 2) {
                assertTrue(mesh.offsets()[i] >= -0.032 - 1.0e-12 && mesh.offsets()[i] <= 0.13 + 1.0e-12);
                assertTrue(mesh.offsets()[i + 1] >= -0.18 - 1.0e-12 && mesh.offsets()[i + 1] <= 0.03 + 1.0e-12);
            }
            int middle = mesh.offsets().length / 2 - 1;
            assertEquals(0, mesh.offsets()[middle], 1.0e-12);
            assertEquals(0, mesh.offsets()[middle + 1], 1.0e-12);
        }
    }

    @Test void fittingUsesAvailableSpaceInTheSplatDirectionWhenTheOtherEdgeIsNear() {
        var p = ImpactProjection.fromVelocity(1, -0.02, 0, 1);
        assertEquals(1, p.mesh(0.04, 0, -0.06, 0.94, -0.5, 0.5).fit());
        assertTrue(p.mesh(0.04, 0, -0.06, 0.06, -0.5, 0.5).fit() < 0.3);
    }

    @Test void reversingTheShotReversesTheTailInsteadOfReusingTheSameEllipse() {
        var right = ImpactProjection.fromVelocity(1, -0.02, 0, 1);
        var left = ImpactProjection.fromVelocity(-1, -0.02, 0, 1);
        assertTrue(right.point(HALF, 0, HALF)[0] > 6 * HALF);
        assertTrue(left.point(-HALF, 0, HALF)[0] < -6 * HALF);
        assertTrue(right.point(-HALF, 0, HALF)[0] > -1.25 * HALF);
        assertTrue(left.point(HALF, 0, HALF)[0] < 1.25 * HALF);
    }

    @Test void everyMeshCellKeepsPositiveWindingWithoutFolds() {
        int n = ImpactProjection.SEGMENTS, side = n + 1;
        for (int angle : new int[]{0, 30, 60, 75, 89}) for (int rotation = 0; rotation < 36; rotation++) {
            double[] velocity = direction(1, angle, 0.61);
            double[] points = mesh(ImpactProjection.fromVelocity(velocity[0], velocity[1], velocity[2], 1), rotation * Math.PI / 18).offsets();
            for (int row = 0; row < n; row++) for (int col = 0; col < n; col++) {
                int a = row * side + col, b = a + 1, d = a + side, c = d + 1;
                assertTrue(area(points, a, b, c) > 0 && area(points, a, c, d) > 0, "Warp folded a textured cell");
            }
        }
    }

    private static ImpactProjection.Mesh mesh(ImpactProjection p, double rotation) {
        return p.mesh(HALF, rotation, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }
    private static double along(double[] point, ImpactProjection p) { return point[0] * p.tangentU() + point[1] * p.tangentV(); }
    private static double area(double[] points, int a, int b, int c) {
        return (points[b * 2] - points[a * 2]) * (points[c * 2 + 1] - points[a * 2 + 1])
                - (points[b * 2 + 1] - points[a * 2 + 1]) * (points[c * 2] - points[a * 2]);
    }
    private static double[] direction(int axis, int angle, double azimuth) {
        double[] result = new double[3]; result[axis] = -Math.cos(Math.toRadians(angle));
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        result[u] = Math.sin(Math.toRadians(angle)) * Math.cos(azimuth);
        result[v] = Math.sin(Math.toRadians(angle)) * Math.sin(azimuth);
        return result;
    }
}
