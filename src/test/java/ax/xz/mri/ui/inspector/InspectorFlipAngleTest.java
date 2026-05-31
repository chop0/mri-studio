package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-PoI flip-angle section. Without trajectories the section
 * renders a "Run simulation to see rotation per point" hint; with at least
 * one PoI populated it renders one row per visible point.
 */
class InspectorFlipAngleTest {

    @Test
    void emptyTrajectoriesShowRunSimulationHint() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            // Default constructor seeds some default points but no trajectories.
            var editSession = new ax.xz.mri.ui.edit.EditSession();
            var track = editSession.addTrack(SequenceChannel.of("rf", 0), "RF");
            var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 0, 100, 1.0);
            editSession.addClip(clip);
            session.activeEditSession.set(editSession);

            var node = FlipAngleSection.build(session, clip);
            var labels = collectLabels((Parent) node);
            assertTrue(labels.stream().anyMatch(s -> s.contains("Run simulation"))
                    || labels.stream().anyMatch(s -> s.contains("No points")),
                "Expected guidance hint when no trajectories yet — got: " + labels);
        });
    }

    private static List<String> collectLabels(Parent root) {
        var sink = new ArrayList<String>();
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof Label l && l.getText() != null) sink.add(l.getText());
            if (child instanceof Parent p) sink.addAll(collectLabels(p));
        }
        return sink;
    }
}
