package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Algebraic contract for {@link SlicePlane}: the (u, v) basis is
 * deterministic, the projection drops normal-direction components, and
 * the basis is orthonormal.
 */
class SlicePlaneTest {

    private static final double EPS = 1e-12;

    @Test
    void normalIsNormalised() {
        var p = SlicePlane.of(Vec3.ZERO, new Vec3(2, 0, 0));
        assertEquals(1.0, p.normal().magnitude(), EPS);
        assertEquals(1.0, p.normal().x(), EPS);
    }

    @Test
    void basisIsOrthonormal() {
        for (var n : new Vec3[] {
            Vec3.X, Vec3.Y, Vec3.Z,
            new Vec3(1, 1, 0), new Vec3(1, 1, 1), new Vec3(0.7, -0.2, 0.1)
        }) {
            var p = SlicePlane.of(Vec3.ZERO, n);
            assertEquals(1.0, p.u().magnitude(), EPS, "u must be unit");
            assertEquals(1.0, p.v().magnitude(), EPS, "v must be unit");
            assertEquals(0.0, p.u().dot(p.normal()), 1e-10, "u ⟂ n");
            assertEquals(0.0, p.v().dot(p.normal()), 1e-10, "v ⟂ n");
            assertEquals(0.0, p.u().dot(p.v()), 1e-10, "u ⟂ v");
        }
    }

    @Test
    void basisDerivationIsDeterministic() {
        var a = SlicePlane.of(Vec3.ZERO, new Vec3(0.3, 0.4, 0.5));
        var b = SlicePlane.of(Vec3.ZERO, new Vec3(0.3, 0.4, 0.5));
        assertEquals(a.u(), b.u());
        assertEquals(a.v(), b.v());
    }

    @Test
    void zeroNormalRejected() {
        assertThrows(IllegalArgumentException.class, () -> SlicePlane.of(Vec3.ZERO, Vec3.ZERO));
    }

    @Test
    void signedDistancePerpendicularToPlane() {
        var p = SlicePlane.of(Vec3.ZERO, Vec3.Z);
        assertEquals(1.0, p.signedDistance(new Vec3(0, 0, 1)), EPS);
        assertEquals(-1.5, p.signedDistance(new Vec3(0, 0, -1.5)), EPS);
        assertEquals(0.0, p.signedDistance(new Vec3(7, 9, 0)), EPS);
    }

    @Test
    void projectionDropsNormalComponent() {
        var p = SlicePlane.of(Vec3.ZERO, Vec3.Z);
        var proj = p.project(new Vec3(1, 2, 3));
        assertEquals(1.0, proj.x(), EPS);
        assertEquals(2.0, proj.y(), EPS);
        assertEquals(0.0, proj.z(), EPS);
    }

    @Test
    void sampleAtIsLinearCombinationOfBasis() {
        var p = SlicePlane.of(Vec3.ZERO, Vec3.Y);  // u=+x, v=-z
        // sampleAt(uMetres, vMetres) = origin + u*uMetres + v*vMetres
        var s = p.sampleAt(2e-3, 3e-3);
        assertEquals(2e-3 * p.u().x() + 3e-3 * p.v().x(), s.x(), EPS);
        assertEquals(2e-3 * p.u().y() + 3e-3 * p.v().y(), s.y(), EPS);
        assertEquals(2e-3 * p.u().z() + 3e-3 * p.v().z(), s.z(), EPS);
    }

    @Test
    void withOffsetAlongNormalTranslatesOrigin() {
        var p = SlicePlane.of(Vec3.ZERO, Vec3.Z);
        var shifted = p.withOffsetAlongNormal(5e-3);
        assertEquals(0.0, shifted.origin().x(), EPS);
        assertEquals(0.0, shifted.origin().y(), EPS);
        assertEquals(5e-3, shifted.origin().z(), EPS);
        // Basis is preserved.
        assertEquals(p.u(), shifted.u());
        assertEquals(p.v(), shifted.v());
    }

    @Test
    void axisFactoriesProduceExpectedNormals() {
        assertEquals(Vec3.X, SlicePlane.axisX().normal());
        assertEquals(Vec3.Y, SlicePlane.axisY().normal());
        assertEquals(Vec3.Z, SlicePlane.axisZ().normal());
    }
}
