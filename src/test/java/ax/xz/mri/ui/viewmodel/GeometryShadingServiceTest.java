package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.support.TestSimulationFactory;
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

        // Cursor after the excite + dephase pulse so the ensemble has actual
        // spatially-incoherent transverse magnetisation.
        service.request(geometry, TestSimulationFactory.incoherentTransverseSimulation(), TestSimulationFactory.freePrecessionPulse(), 51.0, reference);

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
        var simulation = TestSimulationFactory.sampleSimulation();
        var pulse = TestSimulationFactory.pulseA();

        service.request(geometry, simulation, pulse, 10.0, new ReferenceFrameViewModel());
        var absolute = geometry.shadingSnapshot.get();
        assertNotNull(absolute);

        var reference = new ReferenceFrameViewModel();
        reference.setReference(new Vec3(0.0, 0.0, 2.0e-3));
        reference.trajectory.set(simulation.singleSpinTrajectory(new Vec3(0.0, 0.0, 2.0e-3)));
        service.request(geometry, simulation, pulse, 10.0, reference);

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
        // the JVM. The new implementation relies on the simulation's per-instance
        // trajectory cache (LRU bounded) and doesn't store any extra trajectories
        // in the shading service itself.
        //
        // We exercise this by running a shading request against a long-step
        // CompiledSimulation and checking that heap usage doesn't explode beyond
        // what the per-position trajectory cache permits. With a 4000-step train
        // each trajectory is ~160 kB; sampling ~1500 grid positions caches ~250 MB
        // worth of trajectories — comfortably below the 1.5 GB ceiling but above
        // the pathological 10+ GB the old grid would have produced.
        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> { });
        var geometry = new GeometryViewModel();

        var longSegments = java.util.List.of(
            new ax.xz.mri.model.sequence.Segment(1e-6, 2_000, 0),
            new ax.xz.mri.model.sequence.Segment(1e-6, 2_000, 0)
        );
        var longPulse = java.util.List.of(
            new ax.xz.mri.model.sequence.PulseSegment(
                java.util.Collections.nCopies(2_000,
                    new ax.xz.mri.model.sequence.PulseStep(new double[]{0, 0, 0, 0}, 0.0))),
            new ax.xz.mri.model.sequence.PulseSegment(
                java.util.Collections.nCopies(2_000,
                    new ax.xz.mri.model.sequence.PulseStep(new double[]{0, 0, 0, 0}, 0.0)))
        );
        var simulation = ax.xz.mri.support.TestSimulationFactory.simulationWith(longSegments, longPulse);

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        service.request(geometry, simulation, longPulse, 10.0, new ReferenceFrameViewModel());
        long heapAfter = rt.totalMemory() - rt.freeMemory();

        var snapshot = geometry.shadingSnapshot.get();
        assertNotNull(snapshot, "Shading should succeed even on long sequences. Status: " + geometry.statusMessage.get());

        long allocBytes = heapAfter - heapBefore;
        assertTrue(allocBytes < 1500L * 1024 * 1024,
            "Long-sequence shading should not allocate over 1.5 GB. Got "
            + (allocBytes / (1024 * 1024)) + " MB");
    }
}
