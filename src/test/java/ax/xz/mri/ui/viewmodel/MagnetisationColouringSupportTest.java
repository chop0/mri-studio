package ax.xz.mri.ui.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagnetisationColouringSupportTest {
    @Test
    void signalProjectionBrightnessFallsBackToExcitationDuringRf() {
        assertEquals(
            0.8,
            MagnetisationColouringSupport.brightnessValue(
                MagnetisationColouringViewModel.BrightnessSource.SIGNAL_PROJECTION,
                0.8,
                0.2,
                false
            ),
            1e-9
        );
        assertTrue(MagnetisationColouringSupport.isSignalProjectionFallbackActive(
            MagnetisationColouringViewModel.BrightnessSource.SIGNAL_PROJECTION,
            false
        ));
    }

    @Test
    void brightnessSelectionSupportsUniformAndSignalModes() {
        assertEquals(
            1.0,
            MagnetisationColouringSupport.brightnessValue(
                MagnetisationColouringViewModel.BrightnessSource.NONE,
                0.4,
                0.1,
                true
            ),
            1e-9
        );
        assertEquals(
            0.1,
            MagnetisationColouringSupport.brightnessValue(
                MagnetisationColouringViewModel.BrightnessSource.SIGNAL_PROJECTION,
                0.4,
                0.1,
                true
            ),
            1e-9
        );
    }
}
