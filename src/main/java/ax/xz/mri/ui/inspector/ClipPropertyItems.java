package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.inspector.editors.AmplitudeEditor;
import ax.xz.mri.ui.inspector.editors.ChannelRouteEditor;
import ax.xz.mri.ui.inspector.editors.DurationEditor;
import ax.xz.mri.ui.inspector.editors.ToggleSwitchEditor;
import javafx.beans.value.ObservableValue;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.property.editor.PropertyEditor;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the {@link PropertySheet.Item} list for a clip — one item per
 * editable field, each backed by a getter that re-reads
 * {@link EditSession#findClip} on demand and a setter that dispatches the
 * corresponding mutation.
 *
 * <p>Why not annotate the {@link SignalClip} record? Two reasons. First, the
 * record is immutable so a generic property setter would have to know how to
 * call {@code .withX} for each field — the same boilerplate either way.
 * Second, the editor needs context the model record doesn't have: the
 * channel's available list (for the route combo), the eigenfield units (for
 * the amplitude readout). We compose those in here so the model stays clean.
 */
public final class ClipPropertyItems {
    private ClipPropertyItems() {}

    public static List<PropertySheet.Item> build(EditSession session, String clipId) {
        return List.of(
            new ClipItem<>("Position", "Clip start time on the timeline", "Timing", Number.class,
                c -> c.startTime(),
                (c, v) -> session.moveClip(clipId, ((Number) v).doubleValue()),
                () -> new DurationEditor(session.dt.get(), session.totalDuration.get())),
            new ClipItem<>("Duration", "Length of the visible window", "Timing", Number.class,
                c -> c.duration(),
                (c, v) -> session.resizeClip(clipId, ((Number) v).doubleValue()),
                () -> new DurationEditor(session.dt.get(), session.totalDuration.get())),
            new ClipItem<>("Stay centred", "Edge resizes mirror — keeps the clip's centre fixed",
                "Timing", Boolean.class,
                SignalClip::stayCentred,
                (c, v) -> session.replaceClip(clipId, c.withStayCentred((Boolean) v)),
                () -> new ToggleSwitchEditor()),
            new ClipItem<>("Amplitude", "Peak amplitude scaling for this clip's shape",
                "Signal", Number.class,
                c -> c.amplitude(),
                (c, v) -> session.setClipAmplitude(clipId, ((Number) v).doubleValue()),
                () -> {
                    var clip = session.findClip(clipId);
                    if (clip == null) return new AmplitudeEditor("");
                    var track = session.findTrack(clip.trackId());
                    var ef = track == null ? null : session.eigenfieldForChannel(track.simChannel());
                    return new AmplitudeEditor(ef == null ? "" : ef.units());
                }),
            new ClipItem<>("Track", "Output channel routing for this clip's track",
                "Routing", SequenceChannel.class,
                c -> session.findTrack(c.trackId()) == null ? null
                                                            : session.findTrack(c.trackId()).simChannel(),
                (c, v) -> {
                    var newCh = (SequenceChannel) v;
                    var track = session.findTrack(c.trackId());
                    if (track != null) session.setTrackSimChannel(track.id(), newCh);
                },
                () -> new ChannelRouteEditor(session.availableOutputChannels()))
        );

        // Shape-specific parameters are handled by a separate section below
        // the PropertySheet — they don't fit the generic key=value model.
    }

    /** Generic clip-property item parameterised by reader/writer + editor factory. */
    private static final class ClipItem<T> implements PropertySheet.Item {
        private final String name, description, category;
        private final Class<T> type;
        private final Function<SignalClip, ?> reader;
        private final WriteFn writer;
        private final java.util.function.Supplier<PropertyEditor<?>> editorFactory;

        @FunctionalInterface
        private interface WriteFn { void write(SignalClip current, Object value); }

        ClipItem(String name, String description, String category, Class<T> type,
                 Function<SignalClip, ?> reader, WriteFn writer,
                 java.util.function.Supplier<PropertyEditor<?>> editorFactory) {
            this.name = name; this.description = description; this.category = category;
            this.type = type;
            this.reader = reader; this.writer = writer;
            this.editorFactory = editorFactory;
        }

        @Override public Class<?> getType()        { return type; }
        @Override public String getCategory()      { return category; }
        @Override public String getName()          { return name; }
        @Override public String getDescription()   { return description; }
        @Override public Object getValue() { return null; /* PropertySheet reads via the editor */ }
        @Override public void setValue(Object value) { /* delegated through the editor */ }
        @Override public Optional<ObservableValue<? extends Object>> getObservableValue() { return Optional.empty(); }

        Object readFrom(SignalClip clip) { return reader.apply(clip); }
        void writeTo(SignalClip clip, Object value) { writer.write(clip, value); }
        PropertyEditor<?> newEditor() { return editorFactory.get(); }
    }

    /**
     * Custom {@link PropertySheet.PropertyEditorFactory} that knows how to wire
     * our {@link ClipItem}s to their {@link PropertyEditor}s and re-read on
     * every revision bump so external mutations (undo, schematic edits) flow
     * back into the editor.
     *
     * <p>The revision listener gates {@code setValue} on actual value change.
     * Without this gate, dragging a clip — which mutates only {@code startTime}
     * but bumps the shared revision — fires every editor's {@code setValue}
     * 60 times per second, clobbering any in-flight typed edit (the user's
     * keystrokes get overwritten by the spinner's stale model value).
     */
    @SuppressWarnings("unchecked")
    public static org.controlsfx.property.editor.PropertyEditor<?> editorFor(
            EditSession session, String clipId, PropertySheet.Item item) {
        if (!(item instanceof ClipItem<?> ci)) return null;
        var editor = ci.newEditor();
        var typedEditor = (org.controlsfx.property.editor.PropertyEditor<Object>) editor;
        var initial = session.findClip(clipId);
        if (initial != null) typedEditor.setValue(ci.readFrom(initial));
        editor.getEditor().focusedProperty().addListener((obs, was, isNow) -> {
            if (!was || isNow) return;
            var current = session.findClip(clipId);
            if (current != null) ci.writeTo(current, editor.getValue());
        });
        session.revision.addListener((obs, o, n) -> {
            var current = session.findClip(clipId);
            if (current == null) return;
            var newValue = ci.readFrom(current);
            if (java.util.Objects.equals(typedEditor.getValue(), newValue)) return;
            typedEditor.setValue(newValue);
        });
        return editor;
    }
}
