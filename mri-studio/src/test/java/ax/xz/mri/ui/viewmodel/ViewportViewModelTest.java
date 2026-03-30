package ax.xz.mri.ui.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportViewModelTest {
    @Test
    void normalizeClampsAllRangesIntoOneConsistentViewport() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(100);

        viewport.vS.set(-25);
        viewport.vE.set(240);
        viewport.tS.set(-10);
        viewport.tE.set(180);
        viewport.tC.set(400);

        assertInvariant(viewport);
        assertEquals(0.0, viewport.vS.get(), 1e-9);
        assertEquals(100.0, viewport.vE.get(), 1e-9);
        assertEquals(100.0, viewport.tC.get(), 1e-9);
    }

    @Test
    void zoomAndPanKeepAnalysisWindowInsideViewport() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(120);
        viewport.setAnalysisWindow(30, 60);
        viewport.zoomViewportAround(45, 0.5);
        viewport.panViewportBy(80);

        assertInvariant(viewport);
        assertTrue(viewport.vE.get() <= viewport.maxTime.get() + 1e-9);
        assertTrue(viewport.tS.get() >= viewport.vS.get() - 1e-9);
    }

    private static void assertInvariant(ViewportViewModel viewport) {
        assertTrue(viewport.vS.get() >= 0);
        assertTrue(viewport.vS.get() <= viewport.tS.get());
        assertTrue(viewport.tS.get() <= viewport.tC.get());
        assertTrue(viewport.tC.get() <= viewport.tE.get());
        assertTrue(viewport.tE.get() <= viewport.vE.get());
        assertTrue(viewport.vE.get() <= viewport.maxTime.get() + 1e-9);
    }
}
