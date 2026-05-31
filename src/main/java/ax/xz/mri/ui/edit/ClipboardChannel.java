package ax.xz.mri.ui.edit;

import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;

import java.util.List;

/**
 * Typed lane on the system clipboard.
 *
 * <p>Each surface that supports cut/copy/paste owns one
 * {@code ClipboardChannel<T>} parameterised on the type it handles
 * (clips, components, isochromat points, etc.). The {@link #format} is the
 * negotiation handle: a paste-target only consumes Cmd+V when the system
 * clipboard advertises its format. Different domains pick different formats
 * so a circuit-component paste doesn't silently corrupt a sequence editor.
 *
 * <p>The default {@link JsonClipboardChannel} serialises Jackson-friendly
 * record types, which covers everything in the project today. A bespoke
 * channel can be provided for non-record models.
 */
public interface ClipboardChannel<T> {
    DataFormat format();
    boolean put(List<T> items);
    List<T> peek();

    default boolean hasContent() {
        return Clipboard.getSystemClipboard().hasContent(format());
    }
}
