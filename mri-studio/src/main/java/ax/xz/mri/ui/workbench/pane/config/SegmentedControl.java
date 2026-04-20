package ax.xz.mri.ui.workbench.pane.config;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Single-select segmented toggle. Compact replacement for a ComboBox when there
 * are just a few options and you want them visible at a glance (e.g.
 * {@code AmplitudeKind.STATIC / REAL / QUADRATURE}).
 *
 * <p>Styled via {@code .segmented-control} and {@code .segment} CSS classes —
 * the active segment also carries the {@code .active} style class.
 */
public final class SegmentedControl<T> extends HBox {
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final List<Segment<T>> segments = new ArrayList<>();

    public SegmentedControl() {
        getStyleClass().add("segmented-control");
        setFillHeight(true);
        value.addListener((obs, o, n) -> refreshActive());
    }

    public SegmentedControl<T> options(List<T> options, Function<T, String> labeller) {
        segments.clear();
        getChildren().clear();
        for (T option : options) {
            var seg = new Segment<>(option, labeller.apply(option));
            seg.button.setOnAction(e -> value.set(option));
            segments.add(seg);
            getChildren().add(seg.button);
        }
        refreshActive();
        return this;
    }

    public ObjectProperty<T> valueProperty() { return value; }
    public T getValue() { return value.get(); }

    public void setValue(T v) { value.set(v); }

    private void refreshActive() {
        T current = value.get();
        for (var seg : segments) {
            boolean isActive = java.util.Objects.equals(seg.value, current);
            if (isActive) {
                if (!seg.button.getStyleClass().contains("active")) seg.button.getStyleClass().add("active");
            } else {
                seg.button.getStyleClass().remove("active");
            }
        }
    }

    private static final class Segment<T> {
        final T value;
        final Button button;
        Segment(T value, String label) {
            this.value = value;
            this.button = new Button(label);
            this.button.getStyleClass().add("segment");
            this.button.setFocusTraversable(false);
        }
    }
}
