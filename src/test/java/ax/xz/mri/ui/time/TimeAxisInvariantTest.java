package ax.xz.mri.ui.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeAxisInvariantTest {
    @Test
    void cursorViewportAndAnalysisAreAllIndependent() {
        var ta = new TimeAxis();
        ta.domain.maxTime.set(2000);

        ta.viewport.setSpan(0, 500);
        ta.analysis.setSpan(1200, 1600);
        ta.cursor.scrubTo(1800);

        assertEquals(0, ta.viewport.start.get(), 1e-9);
        assertEquals(500, ta.viewport.end.get(), 1e-9);
        assertEquals(1200, ta.analysis.start.get(), 1e-9);
        assertEquals(1600, ta.analysis.end.get(), 1e-9);
        assertEquals(1800, ta.cursor.time.get(), 1e-9);
    }

    @Test
    void shrinkingDomainClampsAllSubObjectsWithoutResetting() {
        var ta = new TimeAxis();
        ta.domain.maxTime.set(2000);
        ta.viewport.setSpan(800, 1500);
        ta.analysis.setSpan(900, 1400);
        ta.cursor.scrubTo(1600);

        ta.domain.maxTime.set(1000);

        assertTrue(ta.viewport.end.get() <= 1000 + 1e-9);
        assertTrue(ta.analysis.end.get() <= 1000 + 1e-9);
        assertTrue(ta.cursor.time.get() <= 1000 + 1e-9);
    }

    @Test
    void snapshotRoundTripsExactly() {
        var ta = new TimeAxis();
        ta.domain.maxTime.set(3000);
        ta.viewport.setSpan(500, 1700);
        ta.analysis.setSpan(700, 1400);
        ta.cursor.scrubTo(1100);

        var snap = ta.snapshot();
        var restored = new TimeAxis();
        restored.restore(snap);

        assertEquals(3000, restored.domain.maxTime.get(), 1e-9);
        assertEquals(500, restored.viewport.start.get(), 1e-9);
        assertEquals(1700, restored.viewport.end.get(), 1e-9);
        assertEquals(700, restored.analysis.start.get(), 1e-9);
        assertEquals(1400, restored.analysis.end.get(), 1e-9);
        assertEquals(1100, restored.cursor.time.get(), 1e-9);
    }

    @Test
    void resetToFullRangePutsCursorAtMidpoint() {
        var ta = new TimeAxis();
        ta.domain.maxTime.set(2000);
        ta.viewport.setSpan(100, 200);
        ta.analysis.setSpan(50, 80);
        ta.cursor.scrubTo(0);

        ta.resetToFullRange();

        assertEquals(0, ta.viewport.start.get(), 1e-9);
        assertEquals(2000, ta.viewport.end.get(), 1e-9);
        assertEquals(0, ta.analysis.start.get(), 1e-9);
        assertEquals(2000, ta.analysis.end.get(), 1e-9);
        assertEquals(1000, ta.cursor.time.get(), 1e-9);
    }

    @Test
    void generationBumpAdvancesAndCanBeChecked() {
        var ta = new TimeAxis();
        long captured = ta.generation.current();
        assertTrue(ta.generation.isCurrent(captured));

        ta.generation.bump();

        assertTrue(!ta.generation.isCurrent(captured));
    }
}
