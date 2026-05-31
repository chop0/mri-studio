package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.ui.viewmodel.GeometryShadingSnapshot.CellSample;
import ax.xz.mri.ui.viewmodel.ReferenceFrameViewModel;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consistency tests for the (u, v) slice-plane shading.
 *
 * <p>At the same (u, v, cursor time), the shading cell's state must match
 * what a direct {@link ax.xz.mri.service.simulation.compiled.CompiledSimulation#singleSpinStateAt}
 * call at {@code plane.sampleAt(u, v)} returns. The default plane is the
 * {@code y = 0} slice (legacy φ = 0 half-plane semantics, just expressed in
 * the new {@link SlicePlane} basis).
 */
class GeometryShadingConsistencyTest {

    private static final double DT = 1e-6;
    private static final double B1 = 200e-6;
    private static final double TAU_S = 1e-3;

    private record Train(List<Segment> segments, List<PulseSegment> pulse) {}

    private static Train buildSimpleCpmg(int nEchoes) {
        double gammaB1 = 267.522e6 * B1;
        int n90 = (int) Math.round((Math.PI / 2) / (gammaB1 * DT));
        int n180 = 2 * n90;
        int nTau = (int) Math.round(TAU_S / DT);
        var segments = new ArrayList<Segment>();
        var pulse = new ArrayList<PulseSegment>();
        // Low-field MRI channel layout: [rf_I, rf_Q, gx, gz].
        segments.add(new Segment(DT, 0, n90));
        pulse.add(filled(n90, new double[]{B1, 0, 0, 0}, 1.0));
        segments.add(new Segment(DT, nTau, 0));
        pulse.add(filled(nTau, new double[]{0, 0, 0, 0}, 0.0));
        for (int e = 0; e < nEchoes; e++) {
            segments.add(new Segment(DT, 0, n180));
            pulse.add(filled(n180, new double[]{0, B1, 0, 0}, 1.0));
            segments.add(new Segment(DT, 2 * nTau, 0));
            pulse.add(filled(2 * nTau, new double[]{0, 0, 0, 0}, 0.0));
        }
        return new Train(segments, pulse);
    }

    private static PulseSegment filled(int n, double[] controls, double gate) {
        var s = new ArrayList<PulseStep>(n);
        for (int i = 0; i < n; i++) s.add(new PulseStep(controls.clone(), gate));
        return new PulseSegment(s);
    }

    @Test
    void shadingCellStateMatchesDirectSimulation() {
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("consistency",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();

        var train = buildSimpleCpmg(2);
        var simulation = new SimulationCompiler().compile(config, train.segments(), train.pulse(), repo);

        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> {});
        var geometry = new GeometryViewModel();

        // Pick a cursor time after both refocusing pulses.
        double cursorUs = 5000;
        service.request(geometry, simulation, train.pulse(), cursorUs, new ReferenceFrameViewModel());

        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot, "Shading snapshot should be produced");
        var plane = snapshot.plane();
        assertNotNull(plane);

        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();

        // Spot-check positions spread across the grid: each cell must match
        // a direct singleSpinStateAt call at plane.sampleAt(u, v).
        int mismatches = 0;
        double worstPerpError = 0;
        for (int i = 0; i < us.size(); i += Math.max(1, us.size() / 5)) {
            double u = us.get(i);
            for (int j = 0; j < vs.size(); j += Math.max(1, vs.size() / 5)) {
                double v = vs.get(j);
                CellSample cell = snapshot.cells()[i][j];
                var direct = simulation.singleSpinStateAt(plane.sampleAt(u, v), cursorUs);
                double perpError = Math.abs(cell.mPerp() - direct.mPerp());
                if (perpError > 1e-6) {
                    mismatches++;
                    worstPerpError = Math.max(worstPerpError, perpError);
                }
            }
        }
        assertEquals(0, mismatches,
            "Shading cell should exactly match direct singleSpinStateAt at the sample point. " +
            "Worst |M⊥| disagreement = " + worstPerpError);
    }

    @Test
    void shadingSampleSpacingMatchesFovHalfExtent() {
        // Spacing on each axis should equal the per-axis FOV half-extent
        // projected onto that basis vector, divided by SAMPLES-1.
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("density",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();
        var train = buildSimpleCpmg(0);
        var simulation = new SimulationCompiler().compile(config, train.segments(), train.pulse(), repo);

        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> {});
        var geometry = new GeometryViewModel();
        service.request(geometry, simulation, train.pulse(), 100.0, new ReferenceFrameViewModel());
        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot);

        // Default plane is y = 0 → u = +x, v = -z. The u-extent spans
        // 2*halfX (= 60 mm for the low-field FOV); v-extent spans 2*halfZ.
        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();
        double uExtentMm = (us.get(us.size() - 1) - us.get(0)) * 1e3;
        double vExtentMm = (vs.get(vs.size() - 1) - vs.get(0)) * 1e3;
        // FOV is now derived from substances — sum the largest half-extent
        // across substances on each axis.
        double halfX = 0, halfZ = 0;
        for (var s : simulation.substances()) {
            var h = s.halfExtent();
            halfX = Math.max(halfX, h.x());
            halfZ = Math.max(halfZ, h.z());
        }
        halfX *= 1e3;
        halfZ *= 1e3;
        // u and v together cover roughly the full FOV diagonal projection.
        assertTrue(uExtentMm >= halfX * 1.95,
            "U axis should span the full 2·halfX, got " + uExtentMm + " mm (expected ~" + (2 * halfX) + ")");
        assertTrue(vExtentMm >= halfZ * 1.95,
            "V axis should span the full 2·halfZ, got " + vExtentMm + " mm (expected ~" + (2 * halfZ) + ")");
    }
}
