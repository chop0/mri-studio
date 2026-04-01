package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ActiveCapture;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Resolved capture currently feeding the analysis workspace. */
public final class ActiveCaptureViewModel {
    public final ObjectProperty<ActiveCapture> activeCapture = new SimpleObjectProperty<>();
}
