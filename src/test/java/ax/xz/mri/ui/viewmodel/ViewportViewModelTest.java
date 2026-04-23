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
        assertEquals(-25.0, viewport.vS.get(), 1e-9);
        assertEquals(240.0, viewport.vE.get(), 1e-9);
        assertEquals(0.0, viewport.tS.get(), 1e-9);
        assertEquals(100.0, viewport.tE.get(), 1e-9);
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
        assertTrue(viewport.tS.get() >= viewport.vS.get() - 1e-9);
    }

    @Test
    void analysisExpansionCanGrowViewportInsteadOfClampingBackInsideIt() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(120);
        viewport.setViewport(0, 20);
        viewport.setAnalysisWindow(60, 90);

        assertInvariant(viewport);
        assertEquals(60.0, viewport.tS.get(), 1e-9);
        assertEquals(90.0, viewport.tE.get(), 1e-9);
        assertTrue(viewport.vS.get() <= 60.0 + 1e-9);
        assertTrue(viewport.vE.get() >= 90.0 - 1e-9);
    }

    private static void assertInvariant(ViewportViewModel viewport) {
        assertTrue(viewport.tS.get() >= 0);
        assertTrue(viewport.vS.get() <= viewport.tS.get());
        assertTrue(viewport.tS.get() <= viewport.tC.get());
        assertTrue(viewport.tC.get() <= viewport.tE.get());
        assertTrue(viewport.tE.get() <= viewport.vE.get());
        assertTrue(viewport.tE.get() <= viewport.maxTime.get() + 1e-9);
    }
}
