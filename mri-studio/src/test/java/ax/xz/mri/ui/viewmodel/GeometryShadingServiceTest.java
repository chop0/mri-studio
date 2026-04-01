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
        var service = new GeometryShadingService(new BlochSimulator(), (Executor) Runnable::run, Runnable::run, () -> { });
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
        var service = new GeometryShadingService(new BlochSimulator(), (Executor) Runnable::run, Runnable::run, () -> { });
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
}
