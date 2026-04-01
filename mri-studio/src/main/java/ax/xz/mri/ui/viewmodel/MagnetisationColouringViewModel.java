package ax.xz.mri.ui.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Shared colouring configuration for phase-aware magnetisation views. */
public class MagnetisationColouringViewModel {
    public enum HueSource {
        PHASE("Phase"),
        NONE("None");

        private final String displayName;

        HueSource(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum BrightnessSource {
        EXCITATION("Excitation (|M⊥|)", "Excitation", "excitation brightness"),
        SIGNAL_PROJECTION("Signal Projection", "Signal", "signal-projection brightness"),
        NONE("None", "None", "no brightness");

        private final String displayName;
        private final String summaryName;
        private final String statusName;

        BrightnessSource(String displayName, String summaryName, String statusName) {
            this.displayName = displayName;
            this.summaryName = summaryName;
            this.statusName = statusName;
        }

        public String displayName() {
            return displayName;
        }

        public String summaryName() {
            return summaryName;
        }

        public String statusName() {
            return statusName;
        }
    }

    public final ObjectProperty<HueSource> hueSource = new SimpleObjectProperty<>(HueSource.PHASE);
    public final ObjectProperty<BrightnessSource> brightnessSource =
        new SimpleObjectProperty<>(BrightnessSource.EXCITATION);

    public boolean isOff() {
        return hueSource.get() == HueSource.NONE && brightnessSource.get() == BrightnessSource.NONE;
    }

    public String summaryLabel() {
        var hue = hueSource.get();
        var brightness = brightnessSource.get();
        if (hue == HueSource.NONE && brightness == BrightnessSource.NONE) return "Off";
        if (hue == HueSource.PHASE && brightness == BrightnessSource.NONE) return "Phase";
        if (hue == HueSource.NONE) return brightness.summaryName();
        if (brightness == BrightnessSource.NONE) return "Phase";
        return "Phase + " + brightness.summaryName();
    }

    public String statusLabel() {
        var hue = hueSource.get();
        var brightness = brightnessSource.get();
        if (hue == HueSource.NONE && brightness == BrightnessSource.NONE) return "colouring off";
        if (hue == HueSource.PHASE && brightness == BrightnessSource.NONE) return "phase hue only";
        if (hue == HueSource.NONE) return brightness.statusName() + " only";
        if (brightness == BrightnessSource.NONE) return "phase hue only";
        return "phase hue + " + brightness.statusName();
    }
}
