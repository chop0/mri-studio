package ax.xz.mri.ui.inspector.editors;

import javafx.scene.Node;
import org.controlsfx.control.ToggleSwitch;
import org.controlsfx.property.editor.PropertyEditor;

/**
 * PropertySheet editor for boolean properties — uses ControlsFX
 * {@link ToggleSwitch} for the modern desktop-app feel the plan calls out.
 * A plain {@code CheckBox} would do the job but reads as a forms-era
 * affordance; the toggle reads as the "switch this on" semantic that
 * matches options like "Stay centred".
 */
public final class ToggleSwitchEditor implements PropertyEditor<Boolean> {
    private final ToggleSwitch toggle = new ToggleSwitch();

    public ToggleSwitchEditor() {
        toggle.getStyleClass().add("toggle-switch-editor");
    }

    @Override public Node getEditor() { return toggle; }
    @Override public Boolean getValue() { return toggle.isSelected(); }
    @Override public void setValue(Boolean value) {
        toggle.setSelected(value != null && value);
    }
}
