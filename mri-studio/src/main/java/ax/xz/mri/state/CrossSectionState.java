package ax.xz.mri.state;

import javafx.beans.property.*;

/** Observable parameters for the r-z cross-section view. */
public class CrossSectionState {

    public enum ShadeMode { MP, SIGNAL }

    public final DoubleProperty              halfHeight  = new SimpleDoubleProperty(80);
    public final ObjectProperty<ShadeMode>   shadeMode   = new SimpleObjectProperty<>(ShadeMode.MP);
    public final BooleanProperty             showMpProj  = new SimpleBooleanProperty(false);
}
