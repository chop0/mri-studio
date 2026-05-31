package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.edit.EditSession;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Renders the typed-parameter sub-section of the inspector for whichever
 * {@link ClipShape} variant the clip currently has. Each variant contributes
 * its own row set; the host calls {@link #populate} on every revision and
 * the rows are rebuilt in place.
 *
 * <p>The PropertySheet doesn't fit shape parameters because the parameter
 * <em>set</em> changes with the variant — switching from Sine to Trapezoid
 * means a new row group, not just new values. So this section sits below
 * the PropertySheet and rebuilds when the kind switches.
 */
public final class ShapeParamsBuilder {
    private ShapeParamsBuilder() {}

    public static void populate(VBox box, EditSession session, SignalClip clip) {
        switch (clip.shape()) {
            case ClipShape.Constant __ -> { /* no parameters */ }
            case ClipShape.Sine s -> {
                box.getChildren().add(numberRow("Frequency (Hz)",
                    s.frequencyHz(), 0.1, 1e9, 10,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sine(v, s.phase(), s.cycles())))));
                box.getChildren().add(numberRow("Phase (rad)",
                    s.phase(), -Math.PI * 4, Math.PI * 4, 0.1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sine(s.frequencyHz(), v, s.cycles())))));
                box.getChildren().add(numberRow("Cycles (0 = use frequency)",
                    s.cycles(), 0, 1000, 1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sine(s.frequencyHz(), s.phase(), v)))));
            }
            case ClipShape.Trapezoid t -> {
                box.getChildren().add(numberRow("Rise (μs)",
                    t.riseTime(), 0, clip.duration(), 1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Trapezoid(v, t.flatTime())))));
                box.getChildren().add(numberRow("Flat (μs)",
                    t.flatTime(), 0, clip.duration(), 1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Trapezoid(t.riseTime(), v)))));
            }
            case ClipShape.Gaussian g -> {
                box.getChildren().add(numberRow("σ (μs)",
                    g.sigma(), 0.1, clip.duration() * 2, 1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Gaussian(v)))));
            }
            case ClipShape.Triangle tr -> {
                box.getChildren().add(numberRow("Peak position (0–1)",
                    tr.peakPosition(), 0, 1, 0.05,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Triangle(v)))));
            }
            case ClipShape.Sinc s -> {
                box.getChildren().add(numberRow("Bandwidth (Hz)",
                    s.bandwidthHz(), 0.1, 1e8, 100,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sinc(v, s.centerOffset(), s.windowFactor())))));
                box.getChildren().add(numberRow("Centre offset (μs)",
                    s.centerOffset(), -1e6, 1e6, 1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sinc(s.bandwidthHz(), v, s.windowFactor())))));
                box.getChildren().add(numberRow("Window factor",
                    s.windowFactor(), 0, 4, 0.1,
                    v -> session.replaceClip(clip.id(),
                        clip.withShape(new ClipShape.Sinc(s.bandwidthHz(), s.centerOffset(), v)))));
            }
            case ClipShape.Spline sp -> {
                box.getChildren().add(new Label("Spline points: " + sp.points().size()
                    + " (drag in the timeline)"));
            }
            default -> box.getChildren().add(new Label("(no parameters for this shape)"));
        }
    }

    private static HBox numberRow(String label, double current, double min, double max, double step,
                                  java.util.function.DoubleConsumer write) {
        var spinner = new Spinner<Double>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, current, step));
        spinner.setEditable(true);
        spinner.getEditor().setPrefColumnCount(8);
        spinner.valueProperty().addListener((obs, o, n) -> { if (n != null) write.accept(n); });
        var lbl = new Label(label);
        lbl.setMinWidth(140);
        var row = new HBox(6, lbl, spinner);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
