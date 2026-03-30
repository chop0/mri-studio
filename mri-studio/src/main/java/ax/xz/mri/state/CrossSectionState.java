package ax.xz.mri.state;

import javafx.beans.property.*;

/** Observable parameters for the geometry/cross-section view. */
public class CrossSectionState {

    public enum ShadeMode { MP, SIGNAL }

    public final DoubleProperty              halfHeight      = new SimpleDoubleProperty(80);
    public final ObjectProperty<ShadeMode>   shadeMode       = new SimpleObjectProperty<>(ShadeMode.MP);
    public final BooleanProperty             shadingEnabled  = new SimpleBooleanProperty(false);
    public final BooleanProperty             showMpProj      = new SimpleBooleanProperty(false);
}
