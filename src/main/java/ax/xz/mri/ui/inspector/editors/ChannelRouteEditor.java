package ax.xz.mri.ui.inspector.editors;

import ax.xz.mri.model.sequence.SequenceChannel;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import org.controlsfx.property.editor.PropertyEditor;

import java.util.List;

/**
 * PropertySheet editor for routing a track to one of the active config's
 * available output channels. The combo's items reflect the live channel list
 * passed at construction; the label text is the channel's source-name + sub-index.
 */
public final class ChannelRouteEditor implements PropertyEditor<SequenceChannel> {
    private final ComboBox<SequenceChannel> combo = new ComboBox<>();

    public ChannelRouteEditor(List<SequenceChannel> available) {
        combo.getItems().setAll(available);
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(SequenceChannel ch) {
                return ch == null ? "—" : ch.sourceName() + "[" + ch.subIndex() + "]";
            }
            @Override public SequenceChannel fromString(String s) { return null; }
        });
        combo.getStyleClass().add("channel-route-editor");
    }

    @Override public Node getEditor() { return combo; }
    @Override public SequenceChannel getValue() { return combo.getValue(); }
    @Override public void setValue(SequenceChannel value) {
        if (value == null) { combo.getSelectionModel().clearSelection(); return; }
        if (!combo.getItems().contains(value)) combo.getItems().add(value);
        combo.setValue(value);
    }
}
