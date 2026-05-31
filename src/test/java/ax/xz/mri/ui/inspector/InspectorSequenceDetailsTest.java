package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import ax.xz.mri.ui.workbench.WorkbenchController;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The inspector must show actual sequence metadata when a sequence is open
 * (with or without a clip selected) — name, time step, total duration, track
 * count, sim config name. Pre-fix, the {@code sequenceHeader} was a stub that
 * rendered only the literal text "Sequence" and nothing else.
 */
class InspectorSequenceDetailsTest {

    @Test
    void inspectorShowsSequenceMetadataWhenNoClipSelected() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var controller = new WorkbenchController(session);
            var inspector = new InspectorPane(new PaneContext(session, controller, PaneId.INSPECTOR));

            var editSession = new EditSession();
            var track = editSession.addTrack(SequenceChannel.of("rf", 0), "RF");
            var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 0.0, 1500.0, 1.0);
            editSession.addClip(clip);
            editSession.dt.set(2.5);
            editSession.totalDuration.set(8000);
            // Open a synthetic SequenceDocument so the inspector sees a name.
            var seqDoc = new SequenceDocument(new ProjectNodeId("seq-1"), "My CPMG",
                new ClipSequence(2.5, 8000, List.of(track), List.of(clip)),
                null, null);
            editSession.open(seqDoc);

            session.activeEditSession.set(editSession);
            inspector.applyCss();
            inspector.layout();

            // No clip selected — inspector should still show sequence metadata.
            var labels = collectLabelText(inspector);
            String labelDump = String.join(" / ", labels);
            assertTrue(labels.stream().anyMatch(s -> s.contains("My CPMG")),
                "Inspector must include the sequence name. Got: " + labelDump);
            assertTrue(labels.stream().anyMatch(s -> s.contains("2.5")),
                "Inspector must include the dt value. Got: " + labelDump);
            assertTrue(labels.stream().anyMatch(s -> s.contains("8000")),
                "Inspector must include the total duration. Got: " + labelDump);
            assertTrue(labels.stream().anyMatch(s -> s.contains("track") || s.contains("Track")),
                "Inspector must mention track count. Got: " + labelDump);
        });
    }

    private static List<String> collectLabelText(Parent root) {
        var sink = new java.util.ArrayList<String>();
        walk(root, sink);
        return sink;
    }

    private static void walk(Parent root, List<String> sink) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof Label l && l.getText() != null) sink.add(l.getText());
            if (child instanceof javafx.scene.control.ScrollPane sp && sp.getContent() instanceof Parent p) {
                walk(p, sink);
            } else if (child instanceof Parent p) {
                walk(p, sink);
            }
        }
    }
}
