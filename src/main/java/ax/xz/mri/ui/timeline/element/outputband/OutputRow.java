package ax.xz.mri.ui.timeline.element.outputband;

import ax.xz.mri.model.sequence.RunContext;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.ui.theme.ThemeTokens;
import ax.xz.mri.ui.theme.TraceColours;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import ax.xz.mri.ui.widget.ContextBadge;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * One read-only probe trace row.
 *
 * <p>Sits beneath the editable track lanes and shows a probe's signal at the
 * same time scale — a sample at time {@code t} in the trace lands at the same
 * X as a clip starting at {@code t} above. The plot uses {@link TraceColours}
 * for sim-vs-hardware visual identity (no more inline hex literals).
 */
public final class OutputRow extends HBox {
    private static final double LABEL_WIDTH = 160;

    private final RunContext context;
    private final String probeName;
    private final Canvas canvas = new Canvas();
    private final TimelineMetrics metrics;
    private final ChangeListener<Number> repaintListener = (obs, o, n) -> repaint();

    private SignalTrace trace;
    private double sharedMaxAbs = 1.0;

    public OutputRow(TimelineMetrics metrics, RunContext context, String probeName) {
        this.metrics = metrics;
        this.context = context;
        this.probeName = probeName;
        getStyleClass().add("output-row");
        setMinHeight(28);
        setPrefHeight(36);

        // Sim/hw context badge + probe name in a row instead of "S "/"H "
        // string prefix. Reads at a glance — colour-coded badge does the
        // typing, the label stays clean.
        var badge = context == RunContext.SIMULATION ? ContextBadge.sim() : ContextBadge.hw();
        var label = new Label(probeName);
        label.getStyleClass().addAll("output-row-label",
            context == RunContext.SIMULATION ? "sim" : "hw");
        var labelBox = new HBox(6, badge, label);
        labelBox.setAlignment(Pos.CENTER_LEFT);
        labelBox.setMinWidth(LABEL_WIDTH);
        labelBox.setMaxWidth(LABEL_WIDTH);
        labelBox.setPrefWidth(LABEL_WIDTH);
        labelBox.setStyle("-fx-padding: 0 8 0 8;");

        var canvasHost = new StackPane(canvas);
        canvasHost.getStyleClass().add("output-row-plot");
        canvasHost.getStyleClass().add(context == RunContext.SIMULATION ? "sim" : "hw");
        HBox.setHgrow(canvasHost, Priority.ALWAYS);
        // Cap Canvas dimensions at JavaFX's hardware texture ceiling
        // (16384 px). Without the cap, a high-zoom viewport on a long
        // sequence pushes the lane area's width past the limit and the
        // texture init throws RuntimeException, blanking the whole pane.
        canvas.widthProperty().bind(Bindings.min(canvasHost.widthProperty(), 16000));
        canvas.heightProperty().bind(Bindings.min(canvasHost.heightProperty(), 16000));

        getChildren().addAll(labelBox, canvasHost);

        canvas.widthProperty().addListener(repaintListener);
        canvas.heightProperty().addListener(repaintListener);
        metrics.timeAxis.viewport.start.addListener(repaintListener);
        metrics.timeAxis.viewport.end.addListener(repaintListener);

        installContextMenu();
    }

    /**
     * HFSS-style right-click menu for a probe row. Reset zoom / Copy data /
     * Export as image / Hide row — items follow the studio's
     * {@link ax.xz.mri.ui.menu.ContextMenuVocabulary} so the menu reads
     * identically to right-click menus on every other plot in the studio.
     */
    private void installContextMenu() {
        setOnContextMenuRequested(e -> {
            var menu = new javafx.scene.control.ContextMenu();
            menu.getItems().addAll(
                ax.xz.mri.ui.menu.ContextMenuVocabulary.RESET_VIEW.item(() ->
                    metrics.timeAxis.viewport.fit()),
                ax.xz.mri.ui.menu.ContextMenuVocabulary.separator(),
                ax.xz.mri.ui.menu.ContextMenuVocabulary.COPY_DATA.item(this::copyDataToClipboard),
                ax.xz.mri.ui.menu.ContextMenuVocabulary.EXPORT_PNG.item(this::exportPngToFile)
            );
            ax.xz.mri.ui.menu.ActiveContextMenu.show(menu, this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void copyDataToClipboard() {
        if (trace == null || trace.points().isEmpty()) return;
        var sb = new StringBuilder("t_us\treal\timag\n");
        for (var p : trace.points()) {
            sb.append(p.tMicros()).append('\t')
              .append(p.real()).append('\t')
              .append(p.imag()).append('\n');
        }
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(sb.toString());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private void exportPngToFile() {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export probe trace as PNG");
        chooser.setInitialFileName(probeName + ".png");
        chooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("PNG image", "*.png"));
        var window = getScene() == null ? null : getScene().getWindow();
        var file = chooser.showSaveDialog(window);
        if (file == null) return;
        var img = canvas.snapshot(null, null);
        try {
            javax.imageio.ImageIO.write(
                javafx.embed.swing.SwingFXUtils.fromFXImage(img, null), "png", file);
        } catch (java.io.IOException ignore) { /* best-effort export */ }
    }

    public RunContext context() { return context; }
    public String probeName()   { return probeName; }

    /** Set the trace (or null to clear) and request repaint. */
    public void setTrace(SignalTrace trace) {
        this.trace = trace;
        repaint();
    }

    /** Inform this row of the band-wide max amplitude so all rows share a vertical scale. */
    public void setSharedMaxAbs(double maxAbs) {
        if (Math.abs(this.sharedMaxAbs - maxAbs) < 1e-9) return;
        this.sharedMaxAbs = Math.max(1e-9, maxAbs);
        repaint();
    }

    private void repaint() {
        var g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0 || trace == null || trace.points().isEmpty()) return;

        boolean sim = context == RunContext.SIMULATION;
        Color line = sim ? TraceColours.SIM_LINE : TraceColours.HW_LINE;
        Color fill = sim ? TraceColours.SIM_FILL : TraceColours.HW_FILL;

        double vS = metrics.timeAxis.viewport.start.get();
        double vE = metrics.timeAxis.viewport.end.get();
        double span = vE - vS;
        if (span <= 0) return;

        double midY = h * 0.5;
        double scale = midY / sharedMaxAbs;

        // Real component fill + line.
        g.setFill(fill);
        g.beginPath();
        boolean started = false;
        for (var p : trace.points()) {
            if (p.tMicros() < vS || p.tMicros() > vE) continue;
            double x = (p.tMicros() - vS) / span * w;
            double y = midY - p.real() * scale;
            if (!started) { g.moveTo(x, midY); g.lineTo(x, y); started = true; }
            else g.lineTo(x, y);
        }
        if (started) {
            g.lineTo(w, midY);
            g.closePath();
            g.fill();
        }

        g.setStroke(line);
        g.setLineWidth(1.2);
        g.beginPath();
        started = false;
        for (var p : trace.points()) {
            if (p.tMicros() < vS || p.tMicros() > vE) continue;
            double x = (p.tMicros() - vS) / span * w;
            double y = midY - p.real() * scale;
            if (!started) { g.moveTo(x, y); started = true; }
            else g.lineTo(x, y);
        }
        if (started) g.stroke();

        // Mid-line for visual reference.
        g.setStroke(ThemeTokens.Tone.BORDER_SUBTLE);
        g.setLineWidth(ThemeTokens.Stroke.HAIRLINE);
        g.strokeLine(0, midY, w, midY);
    }
}
