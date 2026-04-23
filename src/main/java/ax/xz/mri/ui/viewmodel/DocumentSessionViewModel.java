package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;

/**
 * Active document: the field map that analysis panes read from and the pulse
 * currently being simulated.
 */
public class DocumentSessionViewModel {
    public final ObjectProperty<BlochData> blochData = new SimpleObjectProperty<>();
    public final ObjectProperty<List<PulseSegment>> currentPulse = new SimpleObjectProperty<>();

    public void clearDocument() {
        blochData.set(null);
        currentPulse.set(null);
    }
}
