package ax.xz.mri.ui.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportTest {
    @Test
    void setSpanInsideDomainHonoursValues() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var viewport = new Viewport(domain);

        viewport.setSpan(200, 700);

        assertEquals(200, viewport.start.get(), 1e-9);
        assertEquals(700, viewport.end.get(), 1e-9);
    }

    @Test
    void setSpanBeyondDomainClampsBothEnds() {
        var domain = new Domain();
        domain.maxTime.set(500);
        var viewport = new Viewport(domain);

        viewport.setSpan(-50, 800);

        assertTrue(viewport.start.get() >= 0);
        assertTrue(viewport.end.get() <= 500 + 1e-9);
    }

    @Test
    void zoomInPreservesAnchorTime() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var viewport = new Viewport(domain);
        viewport.setSpan(0, 1000);

        viewport.zoomAround(500, 0.5);

        // Anchor time stays at the same fractional position in the new span.
        assertEquals(500, (viewport.start.get() + viewport.end.get()) / 2, 1.0);
        assertTrue(viewport.span() < 1000);
    }

    @Test
    void panByShiftsBothEndsTogether() {
        var domain = new Domain();
        domain.maxTime.set(1000);
        var viewport = new Viewport(domain);
        viewport.setSpan(100, 400);

        viewport.panBy(150);

        assertEquals(250, viewport.start.get(), 1e-9);
        assertEquals(550, viewport.end.get(), 1e-9);
    }

    @Test
    void shrinkingMaxTimePullsViewportInsteadOfResettingIt() {
        // The legacy model reset the viewport to the full range on any maxTime
        // change. The new model preserves the user's pan/zoom: only the
        // viewport's right edge snaps inward as far as needed.
        var domain = new Domain();
        domain.maxTime.set(1000);
        var viewport = new Viewport(domain);
        viewport.setSpan(200, 700);

        domain.maxTime.set(500);

        assertTrue(viewport.start.get() >= 0);
        assertTrue(viewport.end.get() <= 500 + 1e-9);
        assertTrue(viewport.span() <= 500 + 1e-9);
        assertTrue(viewport.span() >= Viewport.MIN_SPAN - 1e-9);
    }

    @Test
    void fitSpansFullDomain() {
        var domain = new Domain();
        domain.maxTime.set(750);
        var viewport = new Viewport(domain);
        viewport.setSpan(200, 300);

        viewport.fit();

        assertEquals(0, viewport.start.get(), 1e-9);
        assertEquals(750, viewport.end.get(), 1e-9);
    }
}
