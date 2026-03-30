package ax.xz.mri.ui.viewmodel;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

/** Cross-section/geometry pane state and cached shading output. */
public class GeometryViewModel {
    public enum ShadeMode { OFF, MP, SIGNAL }

    public final javafx.beans.property.DoubleProperty halfHeight = new javafx.beans.property.SimpleDoubleProperty(80);
    public final ObjectProperty<ShadeMode> shadeMode = new SimpleObjectProperty<>(ShadeMode.OFF);
    public final BooleanProperty showSliceOverlay = new SimpleBooleanProperty(true);
    public final BooleanProperty showLabels = new SimpleBooleanProperty(true);
    public final ObjectProperty<GeometryShadingSnapshot> shadingSnapshot = new SimpleObjectProperty<>();
    public final BooleanProperty shadingComputing = new SimpleBooleanProperty(false);
    public final BooleanProperty signalModeBlocked = new SimpleBooleanProperty(false);
    public final StringProperty statusMessage = new SimpleStringProperty("");
}
