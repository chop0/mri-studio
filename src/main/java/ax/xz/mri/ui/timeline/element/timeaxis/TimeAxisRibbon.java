package ax.xz.mri.ui.timeline.element.timeaxis;

import ax.xz.mri.ui.theme.ThemeTokens;
import ax.xz.mri.ui.time.TimeAxis;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import ax.xz.mri.util.SiFormat;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Slider;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.controlsfx.control.RangeSlider;

/**
 * Time-axis strip rendered at the top of the editor.
 *
 * <p>Three composed pieces, each pulling its weight:
 * <ul>
 *   <li>a {@link Slider} bound bidirectionally to {@link TimeAxis#cursor}'s
 *       time — click-anywhere-to-scrub, arrow-key nudge, Home/End to bounds,
 *       and accessibility all delivered by the JavaFX control;</li>
 *   <li>a {@link RangeSlider} bound to {@link TimeAxis#analysis}'s start and
 *       end — two thumbs, two value properties, drop-in;</li>
 *   <li>an inner {@link Canvas} that paints ticks + time labels, repainting on
 *       viewport pan/zoom.</li>
 * </ul>
 *
 * <p>This is the part of the rebuild that addresses the plan's "time-axis
 * scrub strip is invisible" pain point: instead of an unmarked drag-band, the
 * ribbon shows ticks at decade and half-decade time intervals (μs / ms / s
 * suffix via {@link SiFormat}) with the current cursor time labeled at the
 * thumb.
 */
public final class TimeAxisRibbon extends StackPane {
    private static final double HEIGHT = 36;

    private final TimelineMetrics metrics;
    private final Canvas ticks = new Canvas();
    private final Slider cursorSlider = new Slider();
    private final RangeSlider analysisRange = new RangeSlider();
    private final Pane sliderLayer = new Pane();
    private final ChangeListener<Number> repaintListener = (obs, o, n) -> repaintTicks();

    public TimeAxisRibbon(TimelineMetrics metrics) {
        this.metrics = metrics;
        getStyleClass().add("time-axis-ribbon");
        setPrefHeight(HEIGHT);
        setMinHeight(HEIGHT);
        setMaxHeight(HEIGHT);

        ticks.setMouseTransparent(true);

        configureCursorSlider();
        configureAnalysisRange();

        sliderLayer.setPrefHeight(HEIGHT);
        sliderLayer.getChildren().addAll(analysisRange, cursorSlider);

        getChildren().addAll(ticks, sliderLayer);
        setPadding(new Insets(0));

        // Re-layout child sliders to span the ribbon's full width and keep
        // their thumbs aligned with the timeline lanes below. The thumbs sit
        // in pixel-space, the ribbon's value-space is microseconds — these
        // line up automatically because both ranges are [0, maxTime].
        widthProperty().addListener((obs, o, n) -> { relayoutSliders(); repaintTicks(); });
        heightProperty().addListener((obs, o, n) -> repaintTicks());
        metrics.timeAxis.viewport.start.addListener(repaintListener);
        metrics.timeAxis.viewport.end.addListener(repaintListener);
        metrics.timeAxis.domain.maxTime.addListener(repaintListener);
        metrics.timeAxis.cursor.time.addListener(repaintListener);
        metrics.timeAxis.analysis.start.addListener(repaintListener);
        metrics.timeAxis.analysis.end.addListener(repaintListener);
    }

    private void configureCursorSlider() {
        cursorSlider.getStyleClass().add("cursor-slider");
        cursorSlider.minProperty().bind(Bindings.createDoubleBinding(
            () -> metrics.timeAxis.viewport.start.get(),
            metrics.timeAxis.viewport.start));
        cursorSlider.maxProperty().bind(Bindings.createDoubleBinding(
            () -> metrics.timeAxis.viewport.end.get(),
            metrics.timeAxis.viewport.end));
        cursorSlider.valueProperty().bindBidirectional(metrics.timeAxis.cursor.time);
        cursorSlider.setShowTickMarks(false);
        cursorSlider.setShowTickLabels(false);
        cursorSlider.setSnapToTicks(false);
        cursorSlider.setBlockIncrement(1);
        cursorSlider.setMouseTransparent(false);
        cursorSlider.setFocusTraversable(true);
    }

    private void configureAnalysisRange() {
        analysisRange.getStyleClass().add("analysis-range");
        analysisRange.minProperty().bind(Bindings.createDoubleBinding(
            () -> metrics.timeAxis.viewport.start.get(),
            metrics.timeAxis.viewport.start));
        analysisRange.maxProperty().bind(Bindings.createDoubleBinding(
            () -> metrics.timeAxis.viewport.end.get(),
            metrics.timeAxis.viewport.end));
        analysisRange.lowValueProperty().bindBidirectional(metrics.timeAxis.analysis.start);
        analysisRange.highValueProperty().bindBidirectional(metrics.timeAxis.analysis.end);
        analysisRange.setShowTickLabels(false);
        analysisRange.setShowTickMarks(false);
        analysisRange.setMouseTransparent(false);
    }

    private void relayoutSliders() {
        double w = getWidth();
        analysisRange.resizeRelocate(0, 4, w, HEIGHT * 0.4);
        cursorSlider.resizeRelocate(0, HEIGHT * 0.5, w, HEIGHT * 0.4);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        ticks.setWidth(getWidth());
        ticks.setHeight(getHeight());
        relayoutSliders();
    }

    private void repaintTicks() {
        var g = ticks.getGraphicsContext2D();
        double w = ticks.getWidth();
        double h = ticks.getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) return;

        double vS = metrics.timeAxis.viewport.start.get();
        double vE = metrics.timeAxis.viewport.end.get();
        double span = vE - vS;
        if (span <= 0) return;

        // Pick a tick spacing that produces ~6–10 labelled ticks across the
        // visible span. Round to a "nice" decade-based step (1, 2, 5, 10…).
        double targetTicks = Math.max(4, Math.min(12, w / 80.0));
        double rawStep = span / targetTicks;
        double step = niceStep(rawStep);
        double firstTick = Math.ceil(vS / step) * step;

        g.setFill(ThemeTokens.Tone.TEXT_TERTIARY);
        g.setStroke(ThemeTokens.Tone.BORDER_SUBTLE);
        g.setLineWidth(ThemeTokens.Stroke.HAIRLINE);
        g.setFont(ThemeTokens.Fonts.SMALL);
        for (double t = firstTick; t <= vE; t += step) {
            double x = (t - vS) / span * w;
            g.strokeLine(x, h - 6, x, h);
            g.fillText(SiFormat.time(t), x + 3, h - 8);
        }

        // Subtle baseline above the slider thumbs, so they read as sitting on
        // a real axis rather than floating.
        g.setStroke(ThemeTokens.Tone.BORDER);
        g.setLineWidth(ThemeTokens.Stroke.HAIRLINE);
        g.strokeLine(0, h - 0.5, w, h - 0.5);
    }

    private static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double mantissa = raw / exp;
        double nice =
            mantissa < 1.5 ? 1
          : mantissa < 3.5 ? 2
          : mantissa < 7.5 ? 5
          : 10;
        return nice * exp;
    }
}
