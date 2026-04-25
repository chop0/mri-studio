package ax.xz.mri.service.circuit.path;

import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldPreviewTest {

    @Test
    void uniformZScriptPeakAndRmsEqualScale() {
        // Bz(r) = 1 everywhere ⇒ |B|_peak = scale; RMS = scale.
        var result = FieldPreview.compute(
            (x, y, z) -> Vec3.of(0, 0, 1),
            /* sensitivity */ 0.5,
            /* current */ 4.0);
        assertEquals(0.5 * 4.0, result.scaleTesla(), 1e-12);
        assertEquals(2.0, result.peakField(), 1e-9);
        assertEquals(2.0, result.rmsField(), 1e-9);
        assertEquals(2.0, result.peakVector().z(), 1e-9);
        assertEquals(0, result.peakVector().x(), 1e-9);
        assertEquals(0, result.peakVector().y(), 1e-9);
    }

    @Test
    void linearGradientPeakLandsAtCornerOfBox() {
        // Bz = z → peak |B| = halfExtent at the +z face of the box.
        var result = FieldPreview.compute(
            (x, y, z) -> Vec3.of(0, 0, z),
            1.0, 1.0,  // scale = 1
            0.10, 11);
        assertEquals(0.10, result.peakField(), 1e-9);
        assertEquals(0.10, Math.abs(result.peakZ()), 1e-9);
    }

    @Test
    void zeroCurrentZerosTheField() {
        var result = FieldPreview.compute(
            (x, y, z) -> Vec3.of(1, 0, 0),
            1.0, 0.0);
        assertEquals(0, result.peakField(), 1e-12);
        assertEquals(0, result.rmsField(), 1e-12);
    }

    @Test
    void scaledScriptMultipliesShape() {
        var script = FieldPreview.scaledScript((x, y, z) -> Vec3.of(0, 0, 2), 0.5, 3.0);
        var v = script.evaluate(0, 0, 0);
        assertEquals(0, v.x(), 1e-12);
        assertEquals(0, v.y(), 1e-12);
        assertEquals(0.5 * 3.0 * 2, v.z(), 1e-12);
    }

    @Test
    void scaledScriptToleratesScriptThrowing() {
        var script = FieldPreview.scaledScript((x, y, z) -> { throw new RuntimeException("boom"); }, 1, 1);
        var v = script.evaluate(0, 0, 0);
        assertEquals(Vec3.ZERO, v);
    }

    @Test
    void formatTeslaPicksAppropriateSiPrefix() {
        assertEquals("0 T", FieldPreview.formatTesla(0));
        assertEquals("1.500 T", FieldPreview.formatTesla(1.5));
        assertEquals("15.000 mT", FieldPreview.formatTesla(0.015));
        assertEquals("200.000 µT", FieldPreview.formatTesla(200e-6));
        assertEquals("5.000 nT", FieldPreview.formatTesla(5e-9));
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () ->
            FieldPreview.compute((x, y, z) -> Vec3.ZERO, 1, 1, 0, 5));
        assertThrows(IllegalArgumentException.class, () ->
            FieldPreview.compute((x, y, z) -> Vec3.ZERO, 1, 1, 0.1, 1));
    }
}
