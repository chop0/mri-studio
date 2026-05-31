package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.edit.EditSession;
import javafx.scene.Parent;
import javafx.scene.control.TitledPane;
import org.controlsfx.control.PropertySheet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The inspector's clip property sheet must NOT structurally rebuild when the
 * user drags a clip. A drag mutates only {@code startTime} but bumps the
 * shared revision counter — the pre-fix behaviour rebuilt the entire scene
 * subtree on every revision bump, collapsing TitledPanes and dropping focus.
 *
 * <p>The contract pinned here:
 * <ol>
 *   <li>The PropertySheet's {@link TitledPane} children stay the SAME instances
 *       across a 100-step drag (no rebuild).</li>
 *   <li>The duration / amplitude / stay-centred / track editors don't fire
 *       value-change listeners (their values didn't change, so the data-equality
 *       gate in {@link ClipPropertyItems#editorFor} short-circuits).</li>
 * </ol>
 */
class InspectorStabilityDuringDragTest {

    @Test
    void propertySheetDoesNotRebuildDuringClipDrag() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new EditSession();
            var track = session.addTrack(SequenceChannel.of("a", 0), "A");
            var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
            session.addClip(clip);

            // Wire up the same machinery the InspectorPane uses.
            var items = javafx.collections.FXCollections.<PropertySheet.Item>observableArrayList();
            items.setAll(ClipPropertyItems.build(session, clip.id()));
            var sheet = new PropertySheet(items);
            sheet.setMode(PropertySheet.Mode.CATEGORY);
            sheet.setPropertyEditorFactory(item -> ClipPropertyItems.editorFor(session, clip.id(), item));

            // Force the skin to materialise the TitledPanes.
            sheet.applyCss();
            sheet.layout();

            var beforeTitled = collectTitledPanes(sheet);
            // Snapshot the duration spinner's listener fires.
            var durationFires = new AtomicInteger();
            var amplitudeFires = new AtomicInteger();
            for (var pane : beforeTitled) {
                trackSpinnerChanges(pane, "Duration", durationFires);
                trackSpinnerChanges(pane, "Amplitude", amplitudeFires);
            }

            // 100-step drag: only startTime mutates.
            for (int i = 0; i < 100; i++) {
                session.moveClip(clip.id(), 100.0 + i);
            }

            var afterTitled = collectTitledPanes(sheet);
            // Same instances.
            assertEquals(beforeTitled, afterTitled,
                "PropertySheet rebuilt during drag — TitledPanes were replaced");
            assertEquals(0, durationFires.get(),
                "Duration editor fired despite value not changing — the "
                + "ClipPropertyItems revision listener is missing its equality gate");
            assertEquals(0, amplitudeFires.get(),
                "Amplitude editor fired despite value not changing — the "
                + "ClipPropertyItems revision listener is missing its equality gate");
        });
    }

    private static List<TitledPane> collectTitledPanes(Parent root) {
        var found = new ArrayList<TitledPane>();
        walk(root, found);
        return found;
    }

    private static void walk(Parent root, List<TitledPane> sink) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof TitledPane tp) sink.add(tp);
            if (child instanceof Parent p) walk(p, sink);
        }
    }

    private static void trackSpinnerChanges(Parent root, String labelText, AtomicInteger counter) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof javafx.scene.control.Label l && labelText.equals(l.getText())) {
                // The matching spinner is a sibling — find it from the row's children.
                if (root instanceof Parent parent) {
                    for (var sibling : parent.getChildrenUnmodifiable()) {
                        installCounterIfSpinner(sibling, counter);
                    }
                }
                return;
            }
            if (child instanceof Parent p) trackSpinnerChanges(p, labelText, counter);
        }
    }

    private static void installCounterIfSpinner(javafx.scene.Node node, AtomicInteger counter) {
        if (node instanceof javafx.scene.control.Spinner<?> sp) {
            sp.valueProperty().addListener((obs, o, n) -> counter.incrementAndGet());
        } else if (node instanceof Parent p) {
            for (var c : p.getChildrenUnmodifiable()) installCounterIfSpinner(c, counter);
        }
    }
}
