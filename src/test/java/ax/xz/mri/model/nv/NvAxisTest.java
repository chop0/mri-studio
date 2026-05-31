package ax.xz.mri.model.nv;

import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvAxisTest {

    private static final double EPS = 1e-14;

    @Test
    void normalisesAtConstruction() {
        var a = NvAxis.of(3, 0, 4);
        assertEquals(0.6, a.nx(), EPS);
        assertEquals(0.0, a.ny(), EPS);
        assertEquals(0.8, a.nz(), EPS);
    }

    @Test
    void zeroVectorRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NvAxis(0, 0, 0));
    }

    @Test
    void axialAndTransverseDecomposition() {
        var axis = NvAxis.AXIS_PLUS_Z;
        var b = new Vec3(0.6, 0.0, 0.8);
        assertEquals(0.8, axis.axial(b), EPS);
        assertEquals(0.6, axis.transverseMagnitude(b), EPS);
    }

    @Test
    void axialPlusTransverseSquaresGiveMagnitudeSquared() {
        var axis = NvAxis.of(1, 1, 1);
        var b = new Vec3(0.3, -0.4, 0.5);
        double axial = axis.axial(b);
        double perp  = axis.transverseMagnitude(b);
        assertEquals(b.magnitudeSquared(), axial * axial + perp * perp, 1e-12);
    }

    @Test
    void canonical111Constants() {
        // All ⟨111⟩ axes have |axial(Z)| = 1/√3.
        double expected = 1.0 / Math.sqrt(3);
        var z = Vec3.Z;
        assertEquals(expected, Math.abs(NvAxis.AXIS_111.axial(z)),     EPS);
        assertEquals(expected, Math.abs(NvAxis.AXIS_111_BAR.axial(z)), EPS);
        assertEquals(expected, Math.abs(NvAxis.AXIS_1_BAR_11.axial(z)),EPS);
        assertEquals(expected, Math.abs(NvAxis.AXIS_11_BAR_1.axial(z)),EPS);
    }
}
