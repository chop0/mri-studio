package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke + contract tests for {@link Geometry3DCanvas}. Verifies camera
 * presets, plane-property round-tripping, NV-centre snap behaviour, and
 * eigenfield overlay compile.
 */
class Geometry3DCanvasTest {

    @Test
    void cameraPresetsSetThetaPhi() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            c.setIsoView();
            assertEquals(0.6, c.thetaProperty().get(), 1e-12);
            assertEquals(0.3, c.phiProperty().get(), 1e-12);
            c.setPlusZView();
            assertEquals(0.0, c.thetaProperty().get(), 1e-12);
            assertEquals(Math.PI / 2, c.phiProperty().get(), 1e-12);
            c.resetView();
            assertEquals(1.0, c.zoomProperty().get(), 1e-12);
            c.stop();
        });
    }

    @Test
    void planeAxisPresetsAreReachable() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            c.planeProperty().set(SlicePlane.axisX());
            assertEquals(Vec3.X, c.planeProperty().get().normal());
            c.planeProperty().set(SlicePlane.axisY());
            assertEquals(Vec3.Y, c.planeProperty().get().normal());
            c.planeProperty().set(SlicePlane.axisZ());
            assertEquals(Vec3.Z, c.planeProperty().get().normal());
            c.stop();
        });
    }

    @Test
    void snapPlaneNormalToAxisCollapsesTiltedNormal() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            // 88° from +Z (just slightly tilted from +X) — within an 8° tolerance.
            c.planeProperty().set(SlicePlane.of(Vec3.ZERO, new Vec3(0.9962, 0, 0.0872)));
            c.snapPlaneNormalToAxis(8);
            assertEquals(Vec3.X, c.planeProperty().get().normal(),
                "Near-axis normals must snap exactly to ±X / ±Y / ±Z");
            c.stop();
        });
    }

    @Test
    void snapPlaneNormalLeavesArbitraryAxisAlone() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            var arbitrary = new Vec3(0.7, 0.7, 0.14).normalised();
            c.planeProperty().set(SlicePlane.of(Vec3.ZERO, arbitrary));
            c.snapPlaneNormalToAxis(5);
            // No snap should happen — the normal is far from any axis.
            assertEquals(arbitrary.x(), c.planeProperty().get().normal().x(), 1e-9);
            assertEquals(arbitrary.y(), c.planeProperty().get().normal().y(), 1e-9);
            c.stop();
        });
    }

    @Test
    void translatePlaneAlongNormalAffectsOrigin() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            c.planeProperty().set(SlicePlane.axisZ());
            c.translatePlaneAlongNormal(5e-3);
            assertEquals(5e-3, c.planeProperty().get().origin().z(), 1e-12);
            c.stop();
        });
    }

    @Test
    void overlayScriptAcceptsValidEigenfieldAndRejectsGarbage() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            var script = EigenfieldEngine.compile("""
                import module ax.xz.mri;
                class S implements EigenfieldScript {
                    public Vec3 evaluate(double x, double y, double z) { return Vec3.of(0, 0, 1); }
                }
                """);
            c.overlayProperty().set(script);
            assertSame(script, c.overlayProperty().get());

            String err = c.setOverlayScriptSource("this is not Java");
            assertNotNull(err, "Garbage source should return an error message");
            assertNull(c.overlayProperty().get(), "Garbage source should clear the overlay");
            c.stop();
        });
    }

    @Test
    void simulationPropertyRoundTrips() {
        FxTestSupport.runOnFxThread(() -> {
            var c = new Geometry3DCanvas();
            assertNull(c.simulationProperty().get());
            var sim = nvOnlySim();
            c.simulationProperty().set(sim);
            assertSame(sim, c.simulationProperty().get());
            c.stop();
        });
    }

    private static CompiledSimulation nvOnlySim() {
        var geom = new NvArrayGeometry(NvArrayShape.LINEAR_X_UNIFORM, 4, 1e-6, 50e-9,
            NvAxis.AXIS_PLUS_Z, 0L);
        var ensemble = new NvEnsemble(geom, NvPhysics.defaults(), 0L);
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.of(), List.of(), CircuitLayout.empty());
        List<Substance> substances = List.of(ensemble);
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(
            doc, ProjectState.empty(),
            substances, List.<Segment>of(), List.<PulseSegment>of(), 0.01));
    }
}
