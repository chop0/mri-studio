package ax.xz.mri.model.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sanity tests for the Vec3 value type used by eigenfield scripts. */
class Vec3Test {

    @Test
    void constantsHaveExpectedValues() {
        assertEquals(0, Vec3.ZERO.magnitude(), 1e-12);
        assertEquals(1, Vec3.X.magnitude(), 1e-12);
        assertEquals(1, Vec3.Y.magnitude(), 1e-12);
        assertEquals(1, Vec3.Z.magnitude(), 1e-12);
        assertEquals(1, Vec3.X.x());
        assertEquals(1, Vec3.Y.y());
        assertEquals(1, Vec3.Z.z());
    }

    @Test
    void magnitudeMatchesEuclideanNorm() {
        var v = new Vec3(3, 4, 12);
        assertEquals(13, v.magnitude(), 1e-12);
        assertEquals(169, v.magnitudeSquared(), 1e-12);
    }

    @Test
    void scaleMultipliesComponents() {
        var v = new Vec3(1, 2, 3).scale(2);
        assertEquals(2, v.x());
        assertEquals(4, v.y());
        assertEquals(6, v.z());
    }

    @Test
    void plusAndMinusAreInverse() {
        var a = new Vec3(1, 2, 3);
        var b = new Vec3(0.1, -0.2, 0.3);
        assertEquals(a, a.plus(b).minus(b));
    }

    @Test
    void dotAndCrossObeyIdentities() {
        var a = new Vec3(1, 2, 3);
        var b = new Vec3(4, 5, 6);
        assertEquals(a.dot(b), 1*4 + 2*5 + 3*6, 1e-12);
        var c = a.cross(b);
        // a · (a × b) = 0
        assertEquals(0, a.dot(c), 1e-12);
        assertEquals(0, b.dot(c), 1e-12);
    }

    @Test
    void normaliseProducesUnitVector() {
        var u = new Vec3(2, 0, 0).normalised();
        assertEquals(1, u.magnitude(), 1e-12);
        assertEquals(1, u.x(), 1e-12);
    }

    @Test
    void normaliseZeroReturnsZero() {
        assertSame(Vec3.ZERO, Vec3.ZERO.normalised());
    }

    @Test
    void ofFactoryMatchesConstructor() {
        assertEquals(new Vec3(0.1, 0.2, 0.3), Vec3.of(0.1, 0.2, 0.3));
    }
}
