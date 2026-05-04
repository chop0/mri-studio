package ax.xz.mri.ui.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract that viewport ({@code vS/vE}) and analysis window
 * ({@code tS/tE}) are independent — both clamped to {@code [0, maxTime]} but
 * neither dragged by the other.
 */
class ViewportViewModelTest {
    @Test
    void normalizeClampsBothRangesIndependently() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(100);

        viewport.vS.set(-25);
        viewport.vE.set(240);
        viewport.tS.set(-10);
        viewport.tE.set(180);
        viewport.tC.set(400);

        assertTrue(viewport.vS.get() >= 0);
        assertTrue(viewport.vE.get() <= viewport.maxTime.get() + 1e-9);
        assertTrue(viewport.tS.get() >= 0);
        assertTrue(viewport.tE.get() <= viewport.maxTime.get() + 1e-9);
        assertTrue(viewport.vS.get() < viewport.vE.get());
        assertTrue(viewport.tS.get() < viewport.tE.get());
        // Cursor follows the analysis window.
        assertTrue(viewport.tC.get() >= viewport.tS.get());
        assertTrue(viewport.tC.get() <= viewport.tE.get());
    }

    @Test
    void analysisWindowDoesNotDragViewport() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(120);
        viewport.setViewport(0, 20);
        viewport.setAnalysisWindow(60, 90);

        // The analysis window honours the user's request as-is.
        assertEquals(60.0, viewport.tS.get(), 1e-9);
        assertEquals(90.0, viewport.tE.get(), 1e-9);
        // The viewport stays where it was — the analysis window can sit
        // outside it (and the trace pane will clip), without pulling the
        // editor's view along.
        assertEquals(0.0, viewport.vS.get(), 1e-9);
        assertEquals(20.0, viewport.vE.get(), 1e-9);
    }

    @Test
    void viewportZoomDoesNotResizeAnalysisWindow() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(120);
        viewport.setAnalysisWindow(30, 60);
        viewport.zoomViewportAround(45, 0.5);

        // Analysis window is unchanged by viewport zoom.
        assertEquals(30.0, viewport.tS.get(), 1e-9);
        assertEquals(60.0, viewport.tE.get(), 1e-9);
        // Viewport actually shrank around the pivot.
        assertTrue(viewport.vE.get() - viewport.vS.get() < 120.0);
    }

    @Test
    void cursorClampsToAnalysisWindow() {
        var viewport = new ViewportViewModel();
        viewport.maxTime.set(100);
        viewport.setAnalysisWindow(40, 60);

        viewport.setCursor(10);
        assertEquals(40.0, viewport.tC.get(), 1e-9);

        viewport.setCursor(90);
        assertEquals(60.0, viewport.tC.get(), 1e-9);

        viewport.setCursor(50);
        assertEquals(50.0, viewport.tC.get(), 1e-9);
    }
}
