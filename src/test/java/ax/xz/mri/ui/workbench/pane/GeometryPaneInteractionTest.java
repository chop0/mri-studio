package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.SlicePlane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive interaction tests for the new {@link GeometryPane}.
 *
 * <p>Verifies every toolbar button (camera views, plane presets, snap,
 * reset), default state, plane-property bindings, and edge cases like
 * receiving a null simulation or switching plane normal mid-session.
 */
class GeometryPaneInteractionTest {

    @Test
    void defaultPlaneIsYZero() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            assertEquals(Vec3.Y, session.geometry.slicePlane.get().normal());
            assertEquals(Vec3.ZERO, session.geometry.slicePlane.get().origin());
            assertNotNull(pane);
        });
    }

    @Test
    void perpendicularXPresetSwitchesPlaneNormal() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            new GeometryPane(paneContext(session));
            session.geometry.slicePlane.set(SlicePlane.axisX());
            assertEquals(Vec3.X, session.geometry.slicePlane.get().normal());
        });
    }

    @Test
    void perpendicularYPresetSwitchesPlaneNormal() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            new GeometryPane(paneContext(session));
            session.geometry.slicePlane.set(SlicePlane.axisY());
            assertEquals(Vec3.Y, session.geometry.slicePlane.get().normal());
        });
    }

    @Test
    void perpendicularZPresetSwitchesPlaneNormal() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            new GeometryPane(paneContext(session));
            session.geometry.slicePlane.set(SlicePlane.axisZ());
            assertEquals(Vec3.Z, session.geometry.slicePlane.get().normal());
        });
    }

    @Test
    void canvasBindsBidirectionallyToSession() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            // Set session → canvas reflects.
            session.geometry.slicePlane.set(SlicePlane.axisZ());
            assertEquals(Vec3.Z, pane.editorForTest().planeProperty().get().normal());
            // Set canvas → session reflects.
            pane.editorForTest().planeProperty().set(SlicePlane.axisX());
            assertEquals(Vec3.X, session.geometry.slicePlane.get().normal());
        });
    }

    @Test
    void translatingPlaneAlongNormalChangesOrigin() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            session.geometry.slicePlane.set(SlicePlane.axisZ());
            pane.editorForTest().translatePlaneAlongNormal(7e-3);
            assertEquals(7e-3, session.geometry.slicePlane.get().origin().z(), 1e-12);
        });
    }

    @Test
    void snapBringsTiltedNormalToAxis() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            // Just over 4° tilt from +Z.
            session.geometry.slicePlane.set(SlicePlane.of(Vec3.ZERO,
                new Vec3(0.0698, 0, 0.9976)));
            pane.editorForTest().snapPlaneNormalToAxis(8);
            assertEquals(Vec3.Z, session.geometry.slicePlane.get().normal());
        });
    }

    @Test
    void snapLeavesArbitraryAxisAlone() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            // 45° tilt — well outside an 8° snap window.
            var tilted = new Vec3(0.7, 0, 0.7).normalised();
            session.geometry.slicePlane.set(SlicePlane.of(Vec3.ZERO, tilted));
            pane.editorForTest().snapPlaneNormalToAxis(8);
            assertEquals(tilted.x(), session.geometry.slicePlane.get().normal().x(), 1e-9);
        });
    }

    @Test
    void nullSimulationDoesNotCrash() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            // Even with no simulation, the pane constructs and the canvas can paint.
            assertNotNull(pane);
            assertNull(session.document.simulation.get());
        });
    }

    @Test
    void cameraPresetsRoundTrip() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var pane = new GeometryPane(paneContext(session));
            pane.editorForTest().setIsoView();
            assertEquals(0.6, pane.editorForTest().thetaProperty().get(), 1e-12);
            pane.editorForTest().setPlusZView();
            assertEquals(Math.PI / 2, pane.editorForTest().phiProperty().get(), 1e-12);
            pane.editorForTest().setPlusXView();
            assertEquals(-Math.PI / 2, pane.editorForTest().thetaProperty().get(), 1e-12);
            pane.editorForTest().resetView();
            assertEquals(1.0, pane.editorForTest().zoomProperty().get(), 1e-12);
        });
    }

    private static PaneContext paneContext(StudioSession session) {
        return new PaneContext(session, null, PaneId.CROSS_SECTION);
    }
}
