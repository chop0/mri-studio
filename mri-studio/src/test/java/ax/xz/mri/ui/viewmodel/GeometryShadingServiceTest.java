package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryShadingServiceTest {
    @Test
    void signalShadingFallsBackToMperpDuringRfAndClearsOnceRfEnds() {
        var service = new GeometryShadingService((Executor) Runnable::run, Runnable::run, () -> { });
        var geometry = new GeometryViewModel();
        geometry.shadeMode.set(GeometryViewModel.ShadeMode.SIGNAL);

        service.request(geometry, TestBlochDataFactory.sampleDocument(), TestBlochDataFactory.pulseA(), 0.0);

        assertNotNull(geometry.shadingSnapshot.get());
        assertTrue(geometry.signalModeBlocked.get());
        assertTrue(geometry.statusMessage.get().contains("free precession"));

        service.request(geometry, TestBlochDataFactory.sampleDocument(), TestBlochDataFactory.pulseA(), 10.0);

        assertNotNull(geometry.shadingSnapshot.get());
        assertFalse(geometry.signalModeBlocked.get());
        assertTrue(geometry.statusMessage.get().isBlank());
    }
}
