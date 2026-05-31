package ax.xz.mri.ui.edit;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

/**
 * Passive observable bag describing an in-progress timeline gesture.
 *
 * <p>The DAW timeline used to wire snap-line, snap-chip, dim-cursor, and
 * rubber-band overlays through a central gesture router that switched on the
 * gesture kind to drive each piece of UI. That router was the source of much
 * of the timeline's coupling. The replacement is this passive bag: each Skin
 * that owns a gesture writes its state here, and each piece of overlay UI
 * binds to the property it cares about. There is no dispatch — overlays
 * simply react to property changes.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@link #active} — non-null while a gesture is in progress; nulled out
 *       on commit, cancel, or focus loss.
 *   <li>{@link #snapTargetMicros} — populated by the active gesture when it
 *       has a snap target; {@code NaN} otherwise. The snap-line overlay binds
 *       its X position and visibility to this property.
 *   <li>{@link #draggingClipIds} — ids that are currently visually displaced
 *       by a move/resize. Clip Skins style themselves (CSS {@code .dragging})
 *       based on membership.
 * </ul>
 */
public final class EditPreview {
    public final ObjectProperty<GestureKind> active = new SimpleObjectProperty<>(null);
    public final DoubleProperty snapTargetMicros = new SimpleDoubleProperty(Double.NaN);
    public final ObservableSet<String> draggingClipIds =
        FXCollections.observableSet(new java.util.LinkedHashSet<>());

    public enum GestureKind {
        MOVE_CLIP, RESIZE_LEFT, RESIZE_RIGHT, AMPLITUDE,
        SPLINE_POINT, RUBBER_BAND, CREATE_CLIP, TRACK_REORDER
    }

    /** Convenience: clear gesture state. Called on commit/cancel/focus-loss. */
    public void clear() {
        active.set(null);
        snapTargetMicros.set(Double.NaN);
        draggingClipIds.clear();
    }
}
