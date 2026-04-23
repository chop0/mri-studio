package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.service.simulation.BlochSimulator;
import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryShadingServiceTest {
    @Test
    void geometryShadingProvidesExcitationAndSignalProjectionMetrics() {
        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> { });
        var geometry = new GeometryViewModel();
        var reference = new ReferenceFrameViewModel();

        service.request(geometry, TestBlochDataFactory.incoherentTransverseDocument(), TestBlochDataFactory.freePrecessionPulse(), 0.0, reference);

        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot);
        assertTrue(geometry.statusMessage.get().isBlank());

        boolean foundDifferentSignalProjection = false;
        for (var row : snapshot.cells()) {
            for (var cell : row) {
                assertTrue(cell.signalProjection() >= 0);
                assertTrue(cell.signalProjection() <= cell.mPerp() + 1e-9);
                if (Math.abs(cell.signalProjection() - cell.mPerp()) > 1e-4) {
                    foundDifferentSignalProjection = true;
                }
            }
        }
        assertTrue(foundDifferentSignalProjection);
    }

    @Test
    void mpShadingHueCanBeViewedRelativeToReferenceFrame() {
        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> { });
        var geometry = new GeometryViewModel();
        var data = TestBlochDataFactory.sampleDocument();
        var pulse = TestBlochDataFactory.pulseA();

        service.request(geometry, data, pulse, 10.0, new ReferenceFrameViewModel());
        var absolute = geometry.shadingSnapshot.get();
        assertNotNull(absolute);

        var reference = new ReferenceFrameViewModel();
        reference.setReference(0.0, 2.0);
        reference.trajectory.set(BlochSimulator.simulate(data, 0.0, 2.0, pulse));
        service.request(geometry, data, pulse, 10.0, reference);

        var relative = geometry.shadingSnapshot.get();
        assertNotNull(relative);

        double referencePhase = reference.trajectory.get().stepStateAt(10.0).phaseDeg();
        double expected = ReferenceFrameUtil.normalizeDegrees(absolute.cells()[0][0].phaseDeg() - referencePhase);
        assertEquals(expected, relative.cells()[0][0].phaseDeg(), 1e-5);
    }

    @Test
    void longSequenceShadingDoesNotHoldFullTrajectoriesInMemory() {
        // Regression: the old implementation cached a Trajectory[18][~200] grid per
        // (field, pulse) key, which for long CPMG trains would exceed 10 GB and OOM
        // the JVM. The new implementation relies on BlochSimulator's cursor cache
        // and doesn't store any trajectories of its own.
        //
        // We exercise this by running a shading request against a long-step
        // BlochData and checking that heap usage doesn't explode. The threshold
        // (400 MB of NEW allocation) is comfortably above what the rewritten
        // service needs and far below what the old code consumed.
        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> { });
        var geometry = new GeometryViewModel();
        var data = ax.xz.mri.support.TestBlochDataFactory.sampleDocument();

        // Build a long pulse: 20 000 free-precession steps per segment, matching the
        // fixture's two-segment structure.
        var longPulse = java.util.List.of(
            new ax.xz.mri.model.sequence.PulseSegment(
                java.util.Collections.nCopies(20_000,
                    new ax.xz.mri.model.sequence.PulseStep(new double[]{0, 0, 0, 0}, 0.0))),
            new ax.xz.mri.model.sequence.PulseSegment(
                java.util.Collections.nCopies(20_000,
                    new ax.xz.mri.model.sequence.PulseStep(new double[]{0, 0, 0, 0}, 0.0)))
        );
        // Align the field's segment list to the pulse.
        data.field().segments = java.util.List.of(
            new ax.xz.mri.model.sequence.Segment(1e-6, 20_000, 0),
            new ax.xz.mri.model.sequence.Segment(1e-6, 20_000, 0)
        );

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        service.request(geometry, data, longPulse, 10.0, new ReferenceFrameViewModel());
        long heapAfter = rt.totalMemory() - rt.freeMemory();

        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot, "Shading should succeed even on long sequences");

        long allocBytes = heapAfter - heapBefore;
        // The old grid would have allocated well over 1 GB (18 × zSamples × 40000 ×
        // 5 × 8 bytes = ~2.5 GB). A safe upper bound for the new path is 400 MB.
        assertTrue(allocBytes < 400L * 1024 * 1024,
            "Long-sequence shading should not allocate hundreds of MB. Got "
            + (allocBytes / (1024 * 1024)) + " MB");
    }
}
