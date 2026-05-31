package ax.xz.mri.ui.timeline.scrub;

import ax.xz.mri.support.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives every {@link ScrubStrip} gesture through synthetic MouseEvents and
 * asserts the resulting state. Covers:
 * <ul>
 *   <li>cursor (marker) press + drag in MARKER priority</li>
 *   <li>window-edge drag (start + end)</li>
 *   <li>window-body pan in WINDOW priority</li>
 *   <li>click-outside-window-snaps-centre + then pans</li>
 *   <li>scroll-wheel zoom invokes the zoom handler with anchor + factor</li>
 *   <li>double-click invokes the reset handler</li>
 *   <li>min-window-span is enforced</li>
 *   <li>VERTICAL orientation maps Y → value correctly</li>
 *   <li>edge clamping when the strip's value goes outside the domain</li>
 * </ul>
 *
 * <p>The strip is sized to a fixed 1000×40 layout so pixel-domain math
 * is exact and predictable.
 */
class ScrubStripInteractionTest {
    private static final double WIDTH  = 1000;
    private static final double HEIGHT = 40;
    private static final double TOL    = 1e-6;

    @Test
    void clickOnTrackInWindowPriorityCentersWindow() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(100); b.windowEnd.set(200);
            b.minWindowSpan.set(1);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Click at x=600 (value=600). Window centre should snap to 600.
            ScrubStripFx.click(rig.strip(), 600, HEIGHT / 2);
            assertEquals(550, rig.strip().windowStart.get(), TOL);
            assertEquals(650, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void dragWindowBodyPansWindow() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(300); b.windowEnd.set(500);
            b.minWindowSpan.set(1);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Press inside the window at x=400 (value=400). Drag to x=550 (value=550).
            // The body-drag should shift the window by +150.
            ScrubStripFx.press(rig.strip(), 400, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 550, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 550, HEIGHT / 2);
            assertEquals(450, rig.strip().windowStart.get(), TOL);
            assertEquals(650, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void dragWindowStartHandleResizesLeftEdge() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(300); b.windowEnd.set(500);
            b.minWindowSpan.set(1);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Window start handle is centred at x=300 (3px wide on each side).
            // Press exactly on it, drag to x=200 (value=200).
            ScrubStripFx.press(rig.strip(), 300, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 200, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 200, HEIGHT / 2);
            assertEquals(200, rig.strip().windowStart.get(), TOL);
            assertEquals(500, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void dragWindowEndHandleResizesRightEdge() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(300); b.windowEnd.set(500);
            b.minWindowSpan.set(1);
        });
        FxTestSupport.runOnFxThread(() -> {
            ScrubStripFx.press(rig.strip(), 500, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 750, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 750, HEIGHT / 2);
            assertEquals(300, rig.strip().windowStart.get(), TOL);
            assertEquals(750, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void clickInMarkerPriorityScrubsMarker() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.MARKER);
            b.markerEditable.set(true);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.marker.set(0);
            b.windowStart.set(0); b.windowEnd.set(1000);
        });
        FxTestSupport.runOnFxThread(() -> {
            ScrubStripFx.click(rig.strip(), 350, HEIGHT / 2);
            assertEquals(350, rig.strip().marker.get(), TOL);
        });
    }

    @Test
    void dragMarkerHandleScrubsMarker() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.MARKER);
            b.markerEditable.set(true);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.marker.set(200);
            b.windowStart.set(0); b.windowEnd.set(1000);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Marker handle is at x=200 (value=200). Press on it, drag to x=700.
            ScrubStripFx.press(rig.strip(), 200, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 700, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 700, HEIGHT / 2);
            assertEquals(700, rig.strip().marker.get(), TOL);
        });
    }

    @Test
    void doubleClickInvokesReset() throws Exception {
        var resetCalled = new java.util.concurrent.atomic.AtomicInteger();
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(100); b.windowEnd.set(200);
            b.onReset.set(resetCalled::incrementAndGet);
        });
        FxTestSupport.runOnFxThread(() -> {
            ScrubStripFx.doubleClick(rig.strip(), 500, HEIGHT / 2);
            assertEquals(1, resetCalled.get());
        });
    }

    @Test
    void scrollInvokesZoomWithAnchorAndFactor() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(0); b.windowEnd.set(1000);
        });
        var capturedAnchor = new AtomicReference<Double>();
        var capturedFactor = new AtomicReference<Double>();
        FxTestSupport.runOnFxThread(() -> {
            rig.strip().onZoom.set((anchor, factor) -> {
                capturedAnchor.set(anchor);
                capturedFactor.set(factor);
            });
            ScrubStripFx.scroll(rig.strip(), 750, HEIGHT / 2, 30); // positive deltaY → zoom in
            assertEquals(750.0, capturedAnchor.get(), 1.0);
            assertTrue(capturedFactor.get() < 1.0, "Positive deltaY should produce factor<1 (zoom in)");
        });
    }

    @Test
    void minWindowSpanIsEnforcedDuringEdgeDrag() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(200); b.windowEnd.set(500);
            b.minWindowSpan.set(50);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Drag the right edge inward past the minimum — should clamp at start+50.
            ScrubStripFx.press(rig.strip(), 500, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 220, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 220, HEIGHT / 2);
            assertEquals(200, rig.strip().windowStart.get(), TOL);
            assertEquals(250, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void windowStaysWithinDomainOnPan() throws Exception {
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(800); b.windowEnd.set(900);
            b.minWindowSpan.set(1);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Try to pan past the right edge — window should clamp to end of domain.
            ScrubStripFx.press(rig.strip(), 850, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 1500, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 1500, HEIGHT / 2);
            assertEquals(900, rig.strip().windowStart.get(), TOL);
            assertEquals(1000, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void verticalOrientationMapsYToValue() throws Exception {
        var rig = rig(b -> {
            b.orientation.set(ScrubStrip.Orientation.VERTICAL);
            b.priority.set(ScrubStrip.InteractionPriority.WINDOW);
            b.domainStart.set(0); b.domainEnd.set(100);
            b.windowStart.set(40); b.windowEnd.set(60);
            b.minWindowSpan.set(1);
        }, 40, 1000);
        FxTestSupport.runOnFxThread(() -> {
            // Vertical: domainStart=0 maps to bottom (y=1000), domainEnd=100 maps to top (y=0).
            // y=500 corresponds to value=50 (the middle).
            // Click outside the window at y=200 (value=80) should snap window centre to 80.
            ScrubStripFx.click(rig.strip(), 20, 200);
            // Window centre = 80, half-span 10 → start=70, end=90
            assertEquals(70, rig.strip().windowStart.get(), TOL);
            assertEquals(90, rig.strip().windowEnd.get(),   TOL);
        });
    }

    @Test
    void markerEditableTakesPriorityOverWindowBody() throws Exception {
        // In MARKER priority with markerEditable=true, the marker handle is
        // clickable even when it's inside the window. This guards against
        // the regression where the window body's mouse-transparent flag
        // would block marker-handle clicks.
        var rig = rig(b -> {
            b.priority.set(ScrubStrip.InteractionPriority.MARKER);
            b.markerEditable.set(true);
            b.domainStart.set(0); b.domainEnd.set(1000);
            b.windowStart.set(100); b.windowEnd.set(900);
            b.marker.set(500);
        });
        FxTestSupport.runOnFxThread(() -> {
            // Click on the marker (x=500) — should pick up the marker drag,
            // not start a window-body pan.
            ScrubStripFx.press(rig.strip(), 500, HEIGHT / 2);
            ScrubStripFx.drag (rig.strip(), 700, HEIGHT / 2);
            ScrubStripFx.release(rig.strip(), 700, HEIGHT / 2);
            assertEquals(700, rig.strip().marker.get(), TOL);
            assertEquals(100, rig.strip().windowStart.get(), TOL,
                "Window must not move when the marker is dragged");
            assertEquals(900, rig.strip().windowEnd.get(),   TOL);
        });
    }

    // ── Test harness ─────────────────────────────────────────────────────────

    private record Rig(ScrubStrip strip) {}

    private Rig rig(java.util.function.Consumer<ScrubStrip> configure) throws Exception {
        return rig(configure, WIDTH, HEIGHT);
    }

    private Rig rig(java.util.function.Consumer<ScrubStrip> configure,
                    double width, double height) throws Exception {
        var ref = new AtomicReference<ScrubStrip>();
        FxTestSupport.runOnFxThread(() -> {
            var strip = new ScrubStrip();
            strip.setMinSize(width, height);
            strip.setPrefSize(width, height);
            strip.setMaxSize(width, height);
            configure.accept(strip);
            var root = new StackPane(strip);
            var scene = new Scene(root, width, height);
            var stage = new Stage();
            stage.setScene(scene);
            // Don't actually show — laying out + applyCss is enough for hit-tests.
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ref.set(strip);
        });
        assertNotNull(ref.get(), "rig should produce a strip");
        return new Rig(ref.get());
    }
}
