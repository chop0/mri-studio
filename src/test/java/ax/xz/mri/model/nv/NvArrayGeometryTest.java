package ax.xz.mri.model.nv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvArrayGeometryTest {

    private static final double EPS = 1e-15;

    @Test
    void linearXUniformProducesEqualSpacing() {
        var g = new NvArrayGeometry(NvArrayShape.LINEAR_X_UNIFORM, 8, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 0L);
        var cs = g.generate();
        assertEquals(8, cs.size());
        double step = 1e-6 / 8;
        for (int i = 0; i < 8; i++) {
            double expected = -0.5e-6 + step / 2.0 + i * step;
            assertEquals(expected, cs.get(i).xMetres(), EPS);
            assertEquals(0.0,      cs.get(i).yMetres(), EPS);
            assertEquals(50e-9,    cs.get(i).zMetres(), EPS);
        }
    }

    @Test
    void linearXRandomIsSortedAndWithinBounds() {
        var g = new NvArrayGeometry(NvArrayShape.LINEAR_X_RANDOM, 32, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 42L);
        var cs = g.generate();
        assertEquals(32, cs.size());
        for (int i = 1; i < cs.size(); i++) {
            assertTrue(cs.get(i - 1).xMetres() <= cs.get(i).xMetres(),
                "centres should be sorted by x; pair at " + (i - 1) + ", " + i);
        }
        for (var c : cs) {
            assertTrue(c.xMetres() >= -0.5e-6 && c.xMetres() <= 0.5e-6, "x out of bounds");
        }
    }

    @Test
    void linearXRandomBitStableAcrossRuns() {
        var g1 = new NvArrayGeometry(NvArrayShape.LINEAR_X_RANDOM, 16, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 1234L);
        var g2 = new NvArrayGeometry(NvArrayShape.LINEAR_X_RANDOM, 16, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 1234L);
        var a = g1.generate();
        var b = g2.generate();
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i), b.get(i));
    }

    @Test
    void gridXyProducesNbyNGrid() {
        var g = new NvArrayGeometry(NvArrayShape.GRID_XY, 4, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 0L);
        var cs = g.generate();
        assertEquals(16, cs.size());
        double step = 1e-6 / 4;
        // Spot-check the corners.
        assertEquals(-0.5e-6 + step / 2.0, cs.get(0).xMetres(), EPS);
        assertEquals(-0.5e-6 + step / 2.0, cs.get(0).yMetres(), EPS);
        assertEquals(+0.5e-6 - step / 2.0, cs.get(15).xMetres(), EPS);
        assertEquals(+0.5e-6 - step / 2.0, cs.get(15).yMetres(), EPS);
    }
}
