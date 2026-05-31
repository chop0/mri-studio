package ax.xz.mri.ui.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisWindowTest {
    @Test
    void independentOfViewport() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var viewport = new Viewport(domain);
        var analysis = new AnalysisWindow(domain);

        viewport.setSpan(0, 200);
        analysis.setSpan(600, 900);

        assertEquals(0, viewport.start.get(), 1e-9);
        assertEquals(200, viewport.end.get(), 1e-9);
        assertEquals(600, analysis.start.get(), 1e-9);
        assertEquals(900, analysis.end.get(), 1e-9);
    }

    @Test
    void moveStartHonoursValueWithinDomain() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var analysis = new AnalysisWindow(domain);
        analysis.setSpan(100, 800);

        analysis.moveStart(200);

        assertEquals(200, analysis.start.get(), 1e-9);
        assertEquals(800, analysis.end.get(), 1e-9);
    }

    @Test
    void moveByShiftsBothEdgesTogether() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var analysis = new AnalysisWindow(domain);
        analysis.setSpan(100, 400);

        analysis.moveBy(50);

        assertEquals(150, analysis.start.get(), 1e-9);
        assertEquals(450, analysis.end.get(), 1e-9);
    }

    @Test
    void shrinkingMaxTimeClampsWithoutResetting() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var analysis = new AnalysisWindow(domain);
        analysis.setSpan(300, 700);

        domain.maxTime.set(500);

        assertTrue(analysis.start.get() >= 0);
        assertTrue(analysis.end.get() <= 500 + 1e-9);
        assertTrue(analysis.span() >= AnalysisWindow.MIN_SPAN - 1e-9);
    }
}
