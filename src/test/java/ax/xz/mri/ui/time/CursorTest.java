package ax.xz.mri.ui.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CursorTest {
    @Test
    void scrubInsideDomainSetsTimeExactly() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var cursor = new Cursor(domain);

        cursor.scrubTo(450);

        assertEquals(450, cursor.time.get(), 1e-9);
    }

    @Test
    void scrubBelowZeroClampsToZero() {
        var cursor = new Cursor(new Domain());

        cursor.scrubTo(-12);

        assertEquals(0, cursor.time.get(), 1e-9);
    }

    @Test
    void scrubAboveMaxClampsToMax() {
        var domain = new Domain();
        domain.maxTime.set(500);
        var cursor = new Cursor(domain);

        cursor.scrubTo(900);

        assertEquals(500, cursor.time.get(), 1e-9);
    }

    @Test
    void shrinkingMaxTimeBelowCursorClampsCursor() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var cursor = new Cursor(domain);
        cursor.scrubTo(800);

        domain.maxTime.set(400);

        assertEquals(400, cursor.time.get(), 1e-9);
    }

    @Test
    void cursorIsIndependentOfAnalysisWindow() {
        // Pin the design choice: the cursor lives only on [0, maxTime] —
        // there's no analysis-window coupling. The legacy ViewportViewModel
        // forced cursor into the analysis window, which silently dropped
        // scrubs past the window's edges.
        var domain = new Domain();
        domain.maxTime.set(1000);
        var cursor = new Cursor(domain);
        var window = new AnalysisWindow(domain);
        window.setSpan(400, 600);

        cursor.scrubTo(900);

        assertEquals(900, cursor.time.get(), 1e-9);
    }
}
