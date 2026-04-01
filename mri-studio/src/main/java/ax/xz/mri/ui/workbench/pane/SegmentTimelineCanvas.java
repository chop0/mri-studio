package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Horizontal segment timeline strip. Renders proportional blocks for each segment,
 * colour-coded by type (RF vs free). Click to select, right-click for actions.
 */
public final class SegmentTimelineCanvas extends ResizableCanvas {
    private static final Color BG = Color.web("#f5f5f5");
    private static final Color RF_FILL = Color.web("#e3f2fd");
    private static final Color FREE_FILL = Color.web("#eeeeee");
    private static final Color ACCENT = Color.web("#1976d2");
    private static final Color INK = Color.web("#4c6278");
    private static final Color SPARKLINE = ACCENT.deriveColor(0, 1, 1, 0.5);
    private static final Font LABEL_FONT = Font.font("System", javafx.scene.text.FontWeight.BOLD, 11);
    private static final Font DURATION_FONT = Font.font("System", 9);
    private static final double PAD = 4;

    private final SequenceEditSession editSession;
    private boolean dirty = true;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            paint();
        }
    };

    public SegmentTimelineCanvas(SequenceEditSession editSession) {
        this.editSession = editSession;
        setOnResized(this::scheduleRedraw);
        editSession.revision.addListener(obs -> scheduleRedraw());
        editSession.selectedSegmentIndex.addListener(obs -> scheduleRedraw());

        setOnMousePressed(event -> {
            int index = segmentIndexAt(event.getX());
            if (index >= 0) {
                editSession.selectedSegmentIndex.set(index);
            }
            if (event.getButton() == MouseButton.SECONDARY && index >= 0) {
                showContextMenu(index, event.getScreenX(), event.getScreenY());
            }
        });

        timer.start();
    }

    public void dispose() {
        timer.stop();
    }

    private void scheduleRedraw() {
        dirty = true;
    }

    private void paint() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        var g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        // Background
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        var segments = editSession.segments;
        var pulseSegments = editSession.pulseSegments;
        if (segments.isEmpty()) {
            g.setFill(INK);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("No segments", w / 2, h / 2);
            g.setTextAlign(TextAlignment.LEFT);
            return;
        }

        // Compute total duration for proportional sizing
        double totalDuration = 0;
        for (var seg : segments) totalDuration += seg.durationMicros();
        if (totalDuration <= 0) totalDuration = 1;

        double usableWidth = w - PAD * 2;
        double x = PAD;
        int selected = editSession.selectedSegmentIndex.get();

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            double segWidth = Math.max(20, (seg.durationMicros() / totalDuration) * usableWidth);
            boolean isRf = seg.nPulse() > 0;
            boolean isSelected = i == selected;

            // Block fill
            g.setFill(isRf ? RF_FILL : FREE_FILL);
            g.fillRoundRect(x + 1, PAD, segWidth - 2, h - PAD * 2, 4, 4);

            // Selection highlight
            if (isSelected) {
                g.setStroke(ACCENT);
                g.setLineWidth(2);
                g.strokeRoundRect(x + 1, PAD, segWidth - 2, h - PAD * 2, 4, 4);
            } else {
                g.setStroke(INK.deriveColor(0, 1, 1, 0.3));
                g.setLineWidth(0.5);
                g.strokeRoundRect(x + 1, PAD, segWidth - 2, h - PAD * 2, 4, 4);
            }

            // Segment label
            g.setFill(INK);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("Seg " + i, x + segWidth / 2, PAD + 16);

            // Duration label
            g.setFont(DURATION_FONT);
            g.fillText(formatDuration(seg.durationMicros()), x + segWidth / 2, PAD + 28);

            // Type label
            g.fillText(isRf ? "RF" : "Free", x + segWidth / 2, PAD + 40);

            // Sparkline of B1 magnitude (if RF and wide enough)
            if (isRf && segWidth > 40 && i < pulseSegments.size()) {
                drawSparkline(g, pulseSegments.get(i), x + 6, PAD + 46, segWidth - 12, h - PAD * 2 - 52);
            }

            x += segWidth;
        }

        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawSparkline(GraphicsContext g, PulseSegment pulse, double x, double y, double w, double h) {
        if (h < 4 || pulse.steps().isEmpty()) return;
        int nSteps = pulse.steps().size();
        double maxB1 = 0;
        for (var step : pulse.steps()) {
            maxB1 = Math.max(maxB1, step.b1Magnitude());
        }
        if (maxB1 < 1e-12) return;

        g.setStroke(SPARKLINE);
        g.setLineWidth(1.0);
        double[] xs = new double[nSteps];
        double[] ys = new double[nSteps];
        for (int i = 0; i < nSteps; i++) {
            xs[i] = x + (i / (double) (nSteps - 1)) * w;
            ys[i] = y + h - (pulse.steps().get(i).b1Magnitude() / maxB1) * h;
        }
        g.strokePolyline(xs, ys, nSteps);
    }

    private int segmentIndexAt(double mouseX) {
        var segments = editSession.segments;
        if (segments.isEmpty()) return -1;

        double totalDuration = 0;
        for (var seg : segments) totalDuration += seg.durationMicros();
        if (totalDuration <= 0) return -1;

        double usableWidth = getWidth() - PAD * 2;
        double x = PAD;
        for (int i = 0; i < segments.size(); i++) {
            double segWidth = Math.max(20, (segments.get(i).durationMicros() / totalDuration) * usableWidth);
            if (mouseX >= x && mouseX < x + segWidth) return i;
            x += segWidth;
        }
        return -1;
    }

    private void showContextMenu(int segIndex, double screenX, double screenY) {
        var menu = new ContextMenu();
        var insertBefore = new MenuItem("Insert Before");
        insertBefore.setOnAction(event -> {
            editSession.selectedSegmentIndex.set(Math.max(0, segIndex - 1));
            editSession.insertSegmentAfterSelection();
        });
        var insertAfter = new MenuItem("Insert After");
        insertAfter.setOnAction(event -> {
            editSession.selectedSegmentIndex.set(segIndex);
            editSession.insertSegmentAfterSelection();
        });
        var duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(event -> editSession.duplicateSegment(segIndex));
        var delete = new MenuItem("Delete");
        delete.setOnAction(event -> editSession.removeSegment(segIndex));
        delete.setDisable(editSession.segments.size() <= 1);
        menu.getItems().addAll(insertBefore, insertAfter, duplicate, delete);
        menu.show(this, screenX, screenY);
    }

    private static String formatDuration(double micros) {
        if (micros >= 1000) return String.format("%.1f ms", micros / 1000);
        return String.format("%.0f \u03bcs", micros);
    }
}
