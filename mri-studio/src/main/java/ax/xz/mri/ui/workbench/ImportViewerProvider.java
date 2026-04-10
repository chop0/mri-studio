package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.ActiveCapture;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Set;

/**
 * Viewer provider for imported captures. Content: summary header.
 * Timeline and analysis are global tool windows.
 */
public final class ImportViewerProvider implements DocumentEditorProvider {
	private static final Set<PaneId> RELEVANT = Set.of(
		PaneId.TIMELINE, PaneId.CROSS_SECTION, PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
		PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE);

	private final ActiveCapture capture;
	private final VBox root;

	public ImportViewerProvider(ActiveCapture capture, StudioSession session,
	                            WorkbenchController controller) {
		this.capture = capture;

		root = new VBox(8);
		root.setPadding(new Insets(16, 16, 16, 16));
		root.getChildren().add(headerLabel("Capture: " + capture.name()));
		if (capture.scenarioName() != null)
			root.getChildren().add(detailLabel("Scenario: " + capture.scenarioName()));
		if (capture.iterationKey() != null)
			root.getChildren().add(detailLabel("Iteration: " + capture.iterationKey()));
		if (capture.field() != null) {
			root.getChildren().add(detailLabel(String.format(
				"B\u2080: %.4f T  |  T\u2081: %.0f ms  |  T\u2082: %.0f ms",
				capture.field().b0n, capture.field().t1 * 1e3, capture.field().t2 * 1e3)));
			root.getChildren().add(detailLabel(String.format(
				"FOV: %.1f \u00d7 %.1f mm  |  Grid: %d \u00d7 %d",
				capture.field().fovZ * 1e3, capture.field().fovX * 1e3,
				capture.field().zMm != null ? capture.field().zMm.length : 0,
				capture.field().rMm != null ? capture.field().rMm.length : 0)));
		}
		if (capture.pulse() != null) {
			int totalSteps = capture.pulse().stream().mapToInt(p -> p.steps().size()).sum();
			root.getChildren().add(detailLabel(capture.segments().size() + " segments, " + totalSteps + " steps"));
		}
	}

	@Override public Node editorContent() { return root; }
	@Override public Set<PaneId> relevantToolWindows() { return RELEVANT; }

	@Override
	public void activate(StudioSession session) {
		session.activeEditSession.set(null);
		session.project.activeCapture.activeCapture.set(capture);
	}

	@Override public boolean isDirty() { return false; }
	@Override public void save() {}
	@Override public void dispose() {}

	public ActiveCapture capture() { return capture; }

	private static Label headerLabel(String text) {
		var l = new Label(text);
		l.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
		return l;
	}

	private static Label detailLabel(String text) {
		var l = new Label(text);
		l.setStyle("-fx-font-size: 11px; -fx-text-fill: #425466;");
		return l;
	}
}
