package ax.xz.mri.ui.timeline.element.snapchip;

import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import ax.xz.mri.util.SiFormat;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;

/**
 * The snap-time guide and readout chip.
 *
 * <p>While {@link EditSession#preview}'s {@code snapTargetMicros} is finite,
 * a vertical guide line is drawn at the snap-target X and a small chip-shaped
 * label shows the formatted time. Both bind reactively to the snap target —
 * setting it to NaN hides the chip, setting it to a value shows it. The Skin
 * layer therefore needs zero special logic to "show/hide" — that's pure
 * binding.
 */
public final class SnapChip extends Pane {
    private final Line guide = new Line();
    private final Label readout = new Label();

    public SnapChip(EditSession session, TimelineMetrics metrics) {
        getStyleClass().add("snap-chip");
        setMouseTransparent(true);
        setPickOnBounds(false);

        // Clip overflow so the snap line and chip don't paint outside the
        // lane area when the snap target sits at the viewport edge.
        var clipRect = new javafx.scene.shape.Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        guide.getStyleClass().add("snap-line");
        readout.getStyleClass().add("snap-readout");

        var snapTarget = session.preview.snapTargetMicros;
        var visibility = Bindings.createBooleanBinding(
            () -> !Double.isNaN(snapTarget.get()), snapTarget);

        guide.visibleProperty().bind(visibility);
        readout.visibleProperty().bind(visibility);

        guide.startXProperty().bind(Bindings.createDoubleBinding(
            () -> Double.isNaN(snapTarget.get()) ? 0 : metrics.timeToX(snapTarget.get()),
            snapTarget, metrics.pxPerMicro, metrics.timeAxis.viewport.start));
        guide.endXProperty().bind(guide.startXProperty());
        guide.setStartY(0);
        guide.endYProperty().bind(heightProperty());

        readout.textProperty().bind(Bindings.createStringBinding(
            () -> Double.isNaN(snapTarget.get()) ? "" : SiFormat.time(snapTarget.get()),
            snapTarget));
        readout.layoutXProperty().bind(guide.startXProperty().add(6));
        readout.setLayoutY(4);

        getChildren().addAll(guide, readout);
    }

    @Override protected double computePrefWidth(double h)  { return 0; }
    @Override protected double computePrefHeight(double w) { return 0; }
    @Override protected double computeMinWidth(double h)   { return 0; }
    @Override protected double computeMinHeight(double w)  { return 0; }
}
