package ax.xz.mri.ui.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;

import java.util.List;

/**
 * JSON-backed {@link ClipboardChannel} that serialises any Jackson-compatible
 * record. One instance per domain — clips, components, isochromat points —
 * each with a distinct mime type so paste targets only consume their own
 * format.
 *
 * <p>Reuses the project's existing Jackson dependency. The wire payload also
 * lands on the clipboard as plain text so other apps see something useful;
 * a paste from another app is decoded as the empty list (no-op).
 */
public final class JsonClipboardChannel<T> implements ClipboardChannel<T> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataFormat format;
    private final Class<T> type;

    public JsonClipboardChannel(String mimeSuffix, Class<T> type) {
        this.format = registerFormat("application/x-mri-clipboard-" + mimeSuffix);
        this.type = type;
    }

    /** {@link DataFormat} ctor throws if the mime is already registered;
     *  re-use the existing one in that case. */
    private static DataFormat registerFormat(String mime) {
        var existing = DataFormat.lookupMimeType(mime);
        return existing != null ? existing : new DataFormat(mime);
    }

    @Override public DataFormat format() { return format; }

    @Override
    public boolean put(List<T> items) {
        if (items == null || items.isEmpty()) return false;
        try {
            var json = MAPPER.writeValueAsString(items);
            var content = new ClipboardContent();
            content.put(format, json);
            content.putString(json);
            return Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public List<T> peek() {
        var clip = Clipboard.getSystemClipboard();
        if (!clip.hasContent(format)) return List.of();
        if (!(clip.getContent(format) instanceof String s)) return List.of();
        try {
            return MAPPER.readValue(s,
                MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception ex) {
            return List.of();
        }
    }
}
