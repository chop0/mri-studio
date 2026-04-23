package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.sequence.PulseSegment;

import java.util.List;

/** Shared helpers for applying colouring choices across geometry and phase maps. */
public final class MagnetisationColouringSupport {
    public static final String SIGNAL_PROJECTION_FALLBACK_MESSAGE =
        "Signal-projection brightness is only available during free precession; using excitation (|M⊥|) instead.";

    private MagnetisationColouringSupport() {
    }

    public static boolean isSignalProjectionAvailable(FieldMap field, List<PulseSegment> pulse, double tMicros) {
        if (field == null || pulse == null) return false;
        return rfGateAtTime(field, pulse, tMicros) < 0.5;
    }

    public static boolean isSignalProjectionFallbackActive(
        MagnetisationColouringViewModel.BrightnessSource brightnessSource,
        boolean signalProjectionAvailable
    ) {
        return brightnessSource == MagnetisationColouringViewModel.BrightnessSource.SIGNAL_PROJECTION
            && !signalProjectionAvailable;
    }

    public static double brightnessValue(
        MagnetisationColouringViewModel.BrightnessSource brightnessSource,
        double excitation,
        double signalProjection,
        boolean signalProjectionAvailable
    ) {
        return switch (brightnessSource) {
            case EXCITATION -> excitation;
            case SIGNAL_PROJECTION -> signalProjectionAvailable ? signalProjection : excitation;
            case NONE -> 1.0;
        };
    }

    private static double rfGateAtTime(FieldMap field, List<PulseSegment> pulse, double tMicros) {
        double t = 0;
        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = field.segments.get(segmentIndex);
            for (var step : pulse.get(segmentIndex).steps()) {
                if (t * 1e6 >= tMicros) return step.rfGate();
                t += segment.dt();
            }
        }
        return 0;
    }
}
