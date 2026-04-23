package ax.xz.mri.ui.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

/** Presentation model for the timeline pane and its fixed track set. */
public class TimelineViewModel {
    public record TimelineTrack(String label, Color colour, boolean centered) {}

    public final ObservableList<TimelineTrack> tracks = FXCollections.observableArrayList(
        new TimelineTrack("|B\u2081|", Color.web("#e07000"), false),
        new TimelineTrack("Gz", Color.web("#1565c0"), true),
        new TimelineTrack("Gx", Color.web("#d32f2f"), true),
        new TimelineTrack("Sig", Color.web("#2e7d32"), false)
    );

    public final ViewportViewModel viewport;
    public final TimelineViewportController viewportController;

    public TimelineViewModel(ViewportViewModel viewport) {
        this.viewport = viewport;
        this.viewportController = new TimelineViewportController(viewport);
    }
}
