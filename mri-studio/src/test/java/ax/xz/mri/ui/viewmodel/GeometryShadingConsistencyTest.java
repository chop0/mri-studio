package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.service.simulation.BlochSimulator;
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
 * Consistency tests: at the same (r, z, cursor time), the shading cell's state
 * must match what a direct {@link BlochSimulator#simulateTo} call returns.
 *
 * <p>Also verifies that the grid is dense enough that off-axis points (r != 0)
 * are sampled at worst ~1.5 mm from the actual physical position — the CPMG
 * example the user reported ("r=10.8 mm looks excited, but isochromat at
 * (10.8, 8.5) shows near-z on the sphere") should now land within the same
 * cell as its nearest-sample state.
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
        // Low-field MRI channel layout: [rf_I, rf_Q, gx, gz, rx_gate].
        segments.add(new Segment(DT, 0, n90));
        pulse.add(filled(n90, new double[]{B1, 0, 0, 0, 0}, 1.0));
        segments.add(new Segment(DT, nTau, 0));
        pulse.add(filled(nTau, new double[]{0, 0, 0, 0, 1}, 0.0));
        for (int e = 0; e < nEchoes; e++) {
            segments.add(new Segment(DT, 0, n180));
            pulse.add(filled(n180, new double[]{0, B1, 0, 0, 0}, 1.0));
            segments.add(new Segment(DT, 2 * nTau, 0));
            pulse.add(filled(2 * nTau, new double[]{0, 0, 0, 0, 1}, 0.0));
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
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("consistency",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();

        var train = buildSimpleCpmg(2);
        var data = BlochDataFactory.build(config, train.segments(), repo);

        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> {});
        var geometry = new GeometryViewModel();

        // Pick a cursor time after both refocusing pulses.
        double cursorUs = 5000;
        service.request(geometry, data, train.pulse(), cursorUs, new ReferenceFrameViewModel());

        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot, "Shading snapshot should be produced");

        int radialSamples = snapshot.cells().length;
        double rMax = data.field().rMm[data.field().rMm.length - 1];
        var zSamples = snapshot.zSamples();

        // Spot-check ten positions spread across the grid.
        int mismatches = 0;
        double worstPerpError = 0;
        for (int radialIndex = 0; radialIndex < radialSamples; radialIndex += Math.max(1, radialSamples / 5)) {
            double rMm = (double) radialIndex / (radialSamples - 1) * rMax;
            for (int zStride = 0; zStride < zSamples.size(); zStride += Math.max(1, zSamples.size() / 5)) {
                double zMm = zSamples.get(zStride);
                CellSample cell = snapshot.cells()[radialIndex][zStride];
                var direct = BlochSimulator.simulateTo(data, rMm, zMm, train.pulse(), cursorUs);
                double perpError = Math.abs(cell.mPerp() - direct.mPerp());
                if (perpError > 1e-6) {
                    mismatches++;
                    worstPerpError = Math.max(worstPerpError, perpError);
                }
            }
        }
        assertEquals(0, mismatches,
            "Shading cell should exactly match direct simulateTo at the sample point. " +
            "Worst |M⊥| disagreement = " + worstPerpError);
    }

    @Test
    void shadingSampleIsCloseToAnyOffAxisIsochromatPosition() {
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("density",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();
        var train = buildSimpleCpmg(0);
        var data = BlochDataFactory.build(config, train.segments(), repo);

        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> {});
        var geometry = new GeometryViewModel();
        service.request(geometry, data, train.pulse(), 100.0, new ReferenceFrameViewModel());
        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot);

        double rMax = data.field().rMm[data.field().rMm.length - 1];
        int radialSamples = snapshot.cells().length;
        double radialStep = rMax / (radialSamples - 1);
        assertTrue(radialStep <= 1.60,
            "Radial sample spacing should be ≤ 1.6 mm for Phase-Map-R-comparable resolution, got " + radialStep);

        // The user's reported position r = 10.8 mm, z = 8.5 mm.
        // Find nearest sample to (10.8, 8.5) and verify it's close.
        int nearestR = (int) Math.round(10.8 / radialStep);
        double nearestRmm = nearestR * radialStep;
        assertTrue(Math.abs(nearestRmm - 10.8) <= radialStep / 2,
            "Nearest r sample to 10.8 mm should be within " + (radialStep / 2) + " mm, got " + nearestRmm);

        double bestZ = Double.POSITIVE_INFINITY;
        for (var z : snapshot.zSamples()) {
            if (Math.abs(z - 8.5) < Math.abs(bestZ - 8.5)) bestZ = z;
        }
        assertTrue(Math.abs(bestZ - 8.5) <= 0.3,
            "Nearest z sample to 8.5 mm should be within 0.3 mm, got " + bestZ);
    }
}
