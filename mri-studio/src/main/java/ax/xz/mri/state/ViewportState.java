package ax.xz.mri.state;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/** Observable viewport and cursor properties (all in μs). */
public class ViewportState {
    /** Analysis window start. */
    public final DoubleProperty tS = new SimpleDoubleProperty(0);
    /** Analysis window end. */
    public final DoubleProperty tE = new SimpleDoubleProperty(1000);
    /** Viewport (scroll) start — may be wider than tS/tE. */
    public final DoubleProperty vS = new SimpleDoubleProperty(0);
    /** Viewport (scroll) end. */
    public final DoubleProperty vE = new SimpleDoubleProperty(1000);
    /** Cursor time. */
    public final DoubleProperty tC = new SimpleDoubleProperty(0);
    /** Total sequence duration (read-only, set by DocumentState). */
    public final DoubleProperty maxTime = new SimpleDoubleProperty(1000);

    public void resetToFullRange() {
        double max = maxTime.get();
        tS.set(0);    tE.set(max);
        vS.set(0);    vE.set(max);
        tC.set(0);
    }
}
