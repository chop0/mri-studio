package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Five-channel waveform display for the selected segment.
 * Renders B1x, B1y, Gx, Gz, and RF Gate as stacked lane charts.
 * Read-only in Phase 1 — editing interactions added in Phase 3.
 */
public final class WaveformEditorCanvas extends ResizableCanvas {
    private static final Color BG_EVEN = Color.web("#fafafa");
    private static final Color BG_ODD = Color.web("#ffffff");
    private static final Color INK = Color.web("#4c6278");
    private static final Color ZERO_LINE = Color.web("#cccccc");
    private static final Color LIMIT_LINE = Color.web("#c62828");
    private static final Color B1_COLOR = Color.web("#1976d2");
    private static final Color GX_COLOR = Color.web("#2e7d32");
    private static final Color GZ_COLOR = Color.web("#7b1fa2");
    private static final Color GATE_COLOR = Color.web("#ff6f00");
    private static final Font LABEL_FONT = Font.font("System", javafx.scene.text.FontWeight.BOLD, 10);
    private static final Font AXIS_FONT = Font.font("System", 8);
    private static final double LABEL_WIDTH = 52;
    private static final double PAD_TOP = 4;
    private static final double PAD_BOTTOM = 20;
    private static final double PAD_RIGHT = 8;

    private static final String[] CHANNEL_NAMES = {"B\u2081x", "B\u2081y", "Gx", "Gz", "RF Gate"};
    private static final Color[] CHANNEL_COLORS = {B1_COLOR, B1_COLOR, GX_COLOR, GZ_COLOR, GATE_COLOR};
    /** Relative lane height weights: RF channels get more space than gate. */
    private static final double[] LANE_WEIGHTS = {2, 2, 1.5, 1.5, 0.8};
    private static final double TOTAL_WEIGHT;
    static {
        double sum = 0;
        for (double w : LANE_WEIGHTS) sum += w;
        TOTAL_WEIGHT = sum;
    }

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

    public WaveformEditorCanvas(SequenceEditSession editSession) {
        this.editSession = editSession;
        setOnResized(this::scheduleRedraw);
        editSession.revision.addListener(obs -> scheduleRedraw());
        editSession.selectedSegmentIndex.addListener(obs -> scheduleRedraw());
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

        int segIndex = editSession.selectedSegmentIndex.get();
        if (segIndex < 0 || segIndex >= editSession.pulseSegments.size()) {
            g.setFill(INK);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("Select a segment to view its waveform", w / 2, h / 2);
            g.setTextAlign(TextAlignment.LEFT);
            return;
        }

        var pulse = editSession.pulseSegments.get(segIndex);
        var segment = editSession.segments.get(segIndex);
        List<PulseStep> steps = pulse.steps();
        if (steps.isEmpty()) {
            g.setFill(INK);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("Segment has no steps", w / 2, h / 2);
            g.setTextAlign(TextAlignment.LEFT);
            return;
        }

        double plotWidth = w - LABEL_WIDTH - PAD_RIGHT;
        double plotHeight = h - PAD_TOP - PAD_BOTTOM;
        if (plotWidth < 10 || plotHeight < 10) return;

        double laneY = PAD_TOP;
        for (int ch = 0; ch < 5; ch++) {
            double laneHeight = (LANE_WEIGHTS[ch] / TOTAL_WEIGHT) * plotHeight;

            // Lane background
            g.setFill(ch % 2 == 0 ? BG_EVEN : BG_ODD);
            g.fillRect(LABEL_WIDTH, laneY, plotWidth, laneHeight);

            // Channel label
            g.setFill(CHANNEL_COLORS[ch]);
            g.setFont(LABEL_FONT);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(CHANNEL_NAMES[ch], LABEL_WIDTH - 4, laneY + laneHeight / 2 + 4);

            // Zero line
            if (ch < 4) { // not for gate
                double zeroY = laneY + laneHeight / 2;
                g.setStroke(ZERO_LINE);
                g.setLineWidth(0.5);
                g.setLineDashes(4, 3);
                g.strokeLine(LABEL_WIDTH, zeroY, LABEL_WIDTH + plotWidth, zeroY);
                g.setLineDashes();
            }

            // Hardware limit lines
            drawLimitLines(g, ch, laneY, laneHeight, plotWidth);

            // Waveform
            drawChannelWaveform(g, steps, ch, LABEL_WIDTH, laneY, plotWidth, laneHeight);

            // Axis labels
            drawAxisLabels(g, ch, laneY, laneHeight);

            laneY += laneHeight;
        }

        // X-axis labels (step indices with time)
        drawXAxis(g, steps.size(), segment.dt(), LABEL_WIDTH, h - PAD_BOTTOM, plotWidth);

        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawChannelWaveform(GraphicsContext g, List<PulseStep> steps, int ch,
                                      double x0, double laneY, double plotWidth, double laneHeight) {
        int n = steps.size();
        double[] xs = new double[n];
        double[] ys = new double[n];

        double maxVal = channelMax(ch);
        double minVal = channelMin(ch);
        double range = maxVal - minVal;
        if (range < 1e-15) range = 1;

        double margin = laneHeight * 0.08;
        double drawHeight = laneHeight - margin * 2;

        for (int i = 0; i < n; i++) {
            xs[i] = x0 + (i / (double) Math.max(1, n - 1)) * plotWidth;
            double val = channelValue(steps.get(i), ch);
            ys[i] = laneY + margin + (1.0 - (val - minVal) / range) * drawHeight;
        }

        g.setStroke(CHANNEL_COLORS[ch]);
        g.setLineWidth(1.4);
        g.strokePolyline(xs, ys, n);
    }

    private void drawLimitLines(GraphicsContext g, int ch, double laneY, double laneHeight,
                                 double plotWidth) {
        if (ch == 4) return; // gate has no hardware limits

        double maxVal = channelMax(ch);
        double minVal = channelMin(ch);
        double range = maxVal - minVal;
        double margin = laneHeight * 0.08;
        double drawHeight = laneHeight - margin * 2;

        double limitHigh = channelHardwareMax(ch);
        double limitLow = -limitHigh;

        g.setStroke(LIMIT_LINE);
        g.setLineWidth(0.8);
        g.setLineDashes(4, 3);

        double yHigh = laneY + margin + (1.0 - (limitHigh - minVal) / range) * drawHeight;
        double yLow = laneY + margin + (1.0 - (limitLow - minVal) / range) * drawHeight;
        if (yHigh >= laneY && yHigh <= laneY + laneHeight) {
            g.strokeLine(LABEL_WIDTH, yHigh, LABEL_WIDTH + plotWidth, yHigh);
        }
        if (yLow >= laneY && yLow <= laneY + laneHeight) {
            g.strokeLine(LABEL_WIDTH, yLow, LABEL_WIDTH + plotWidth, yLow);
        }

        g.setLineDashes();
    }

    private void drawAxisLabels(GraphicsContext g, int ch, double laneY, double laneHeight) {
        if (ch == 4) return; // gate is 0/1, no axis labels needed

        double limitHigh = channelHardwareMax(ch);
        String unit = ch < 2 ? " \u03bcT" : " mT/m";
        double displayHigh = ch < 2 ? limitHigh * 1e6 : limitHigh * 1e3;

        g.setFill(INK.deriveColor(0, 1, 1, 0.6));
        g.setFont(AXIS_FONT);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(String.format("%.0f%s", displayHigh, unit), LABEL_WIDTH - 4, laneY + 10);
        g.fillText(String.format("%.0f%s", -displayHigh, unit), LABEL_WIDTH - 4, laneY + laneHeight - 2);
    }

    private void drawXAxis(GraphicsContext g, int nSteps, double dt, double x0, double y, double plotWidth) {
        g.setFill(INK);
        g.setFont(AXIS_FONT);
        g.setTextAlign(TextAlignment.CENTER);

        int labelCount = Math.min(10, nSteps);
        for (int i = 0; i <= labelCount; i++) {
            int step = (int) Math.round((double) i / labelCount * (nSteps - 1));
            double x = x0 + (step / (double) Math.max(1, nSteps - 1)) * plotWidth;
            double timeMicros = step * dt * 1e6;
            g.fillText(String.format("%.0f\u03bcs", timeMicros), x, y + 12);

            // Tick mark
            g.setStroke(INK.deriveColor(0, 1, 1, 0.3));
            g.setLineWidth(0.5);
            g.strokeLine(x, y, x, y + 3);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static double channelValue(PulseStep step, int ch) {
        return switch (ch) {
            case 0 -> step.b1x();
            case 1 -> step.b1y();
            case 2 -> step.gx();
            case 3 -> step.gz();
            case 4 -> step.rfGate();
            default -> 0;
        };
    }

    private static double channelHardwareMax(int ch) {
        return switch (ch) {
            case 0, 1 -> HardwareLimits.B1_MAX;
            case 2 -> HardwareLimits.GX_MAX;
            case 3 -> HardwareLimits.GZ_MAX;
            default -> 1;
        };
    }

    private static double channelMax(int ch) {
        // Display range is slightly larger than hardware limits for visual headroom
        return ch == 4 ? 1.1 : channelHardwareMax(ch) * 1.15;
    }

    private static double channelMin(int ch) {
        return ch == 4 ? -0.1 : -channelMax(ch);
    }
}
