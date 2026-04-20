package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipEvaluator;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.ViewportViewModel;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;

import java.util.List;

import static ax.xz.mri.ui.theme.StudioTheme.AC;
import static ax.xz.mri.ui.theme.StudioTheme.BG;
import static ax.xz.mri.ui.theme.StudioTheme.GR;
import static ax.xz.mri.ui.theme.StudioTheme.UI_7;

/**
 * Overview scrubber bar for the sequence editor, showing a compressed view of
 * all channels with zoom/pan interaction via {@link AxisScrubBar}.
 *
 * <p>Renders clip regions as coloured spans and overlays miniature waveform traces.
 * The AxisScrubBar interaction model handles viewport dragging, resizing, and zooming.
 */
public class SequenceOverviewBar extends ResizableCanvas {
    private static final double PAD_L = 52;
    private static final double PAD_R = 6;

    private static final Color B1_COLOUR = Color.web("#1976d2");
    private static final Color GX_COLOUR = Color.web("#2e7d32");
    private static final Color GZ_COLOUR = Color.web("#7b1fa2");
    private static final Color GATE_COLOUR = Color.web("#ff6f00");
    private static final Color CURSOR_COLOUR = Color.web("#e06000");

    private final SequenceEditSession editSession;
    private final ViewportViewModel viewport; // global viewport for cursor + analysis window sync
    private final AxisScrubBar.Interaction interaction;
    private final AnimationTimer timer;
    private boolean dirty = true;
    private boolean syncing; // prevent listener feedback loop

    public SequenceOverviewBar(SequenceEditSession editSession, ViewportViewModel viewport) {
        this.editSession = editSession;
        this.viewport = viewport;

        this.interaction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.HORIZONTAL,
            new AxisScrubBar.WindowModel() {
                @Override public double domainStart() { return 0; }
                @Override public double domainEnd() { return Math.max(1, editSession.totalDuration.get()); }
                @Override public double windowStart() { return editSession.viewStart.get(); }
                @Override public double windowEnd() { return editSession.viewEnd.get(); }
                @Override public void setWindow(double start, double end) {
                    editSession.viewStart.set(start);
                    editSession.viewEnd.set(end);
                }
                @Override public void zoomAround(double anchor, double factor) {
                    editSession.zoomViewAround(anchor, factor);
                }
                @Override public void resetWindow() {
                    editSession.fitView();
                }
            }
        );

        setOnResized(this::scheduleRedraw);

        // Bind redraws to model changes
        editSession.revision.addListener((obs, o, n) -> scheduleRedraw());
        editSession.viewStart.addListener((obs, o, n) -> scheduleRedraw());
        editSession.viewEnd.addListener((obs, o, n) -> scheduleRedraw());
        editSession.totalDuration.addListener((obs, o, n) -> scheduleRedraw());

        // Sync editor viewStart/viewEnd ↔ global viewport vS/vE (main viewport range)
        // This keeps the editor's zoom level and the timeline's zoom level in sync.
        // Also sync with tS/tE (analysis window = blue selection) so they match.
        editSession.viewStart.addListener((obs, o, n) -> {
            if (!syncing) {
                syncing = true;
                viewport.vS.set(n.doubleValue());
                viewport.tS.set(n.doubleValue());
                syncing = false;
            }
        });
        editSession.viewEnd.addListener((obs, o, n) -> {
            if (!syncing) {
                syncing = true;
                viewport.vE.set(n.doubleValue());
                viewport.tE.set(n.doubleValue());
                syncing = false;
            }
        });
        viewport.vS.addListener((obs, o, n) -> {
            if (!syncing) { syncing = true; editSession.viewStart.set(n.doubleValue()); syncing = false; }
        });
        viewport.vE.addListener((obs, o, n) -> {
            if (!syncing) { syncing = true; editSession.viewEnd.set(n.doubleValue()); syncing = false; }
        });
        // Also sync analysis window (tS/tE) → editor viewport, so scrubbing
        // in phase maps / trace plots updates the DAW's visible range
        viewport.tS.addListener((obs, o, n) -> {
            if (!syncing) { syncing = true; editSession.viewStart.set(n.doubleValue()); syncing = false; }
        });
        viewport.tE.addListener((obs, o, n) -> {
            if (!syncing) { syncing = true; editSession.viewEnd.set(n.doubleValue()); syncing = false; }
        });

        // Redraw when cursor moves
        viewport.tC.addListener((obs, o, n) -> scheduleRedraw());

        // Mouse interaction
        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(e -> interaction.handleRelease());
        setOnScroll(this::onScroll);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (dirty) {
                    dirty = false;
                    paint();
                }
            }
        };
        timer.start();
    }

    private void scheduleRedraw() { dirty = true; }

    public void dispose() { timer.stop(); }

    // ==================== Interaction ====================

    private AxisScrubBar.Bounds barBounds() {
        return new AxisScrubBar.Bounds(PAD_L, 0, getWidth() - PAD_L - PAD_R, getHeight());
    }

    private void onMousePressed(MouseEvent e) {
        interaction.handlePress(barBounds(), e);
    }

    private void onMouseDragged(MouseEvent e) {
        interaction.handleDrag(barBounds(), e);
        scheduleRedraw();
    }

    private void onScroll(ScrollEvent e) {
        interaction.handleScroll(barBounds(), e);
        scheduleRedraw();
    }

    // ==================== Rendering ====================

    private void paint() {
        double w = getWidth();
        double h = getHeight();
        var g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        // Background
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        // Label
        g.setFill(Color.web("#707070"));
        g.setFont(UI_7);
        g.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
        g.fillText("Overview", PAD_L - 4, h / 2 + 3);
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);

        var bounds = barBounds();
        double domain = Math.max(1, editSession.totalDuration.get());

        // Build spans from clips (coloured regions)
        var spans = new java.util.ArrayList<AxisScrubBar.Span>();
        for (var clip : editSession.clips) {
            Color colour = channelColour(clip.channel());
            spans.add(new AxisScrubBar.Span(clip.startTime(), clip.endTime(), colour, 0.15));
        }

        // Draw the scrub bar with orange cursor marker
        var markers = List.of(
            new AxisScrubBar.Marker(viewport.tC.get(), CURSOR_COLOUR)
        );
        var spec = AxisScrubBar.Spec.horizontal(
            0, domain,
            editSession.viewStart.get(), editSession.viewEnd.get(),
            spans, markers
        );
        AxisScrubBar.draw(g, bounds, spec);

        // Overlay compressed waveforms
        drawCompressedWaveforms(g, bounds, domain);

        // Bottom border
        g.setStroke(GR);
        g.setLineWidth(0.5);
        g.strokeLine(0, h - 0.5, w, h - 0.5);
    }

    // Cached overview samples — recomputed only when revision or pixel count changes
    private int cachedRevision = -1;
    private int cachedPixelCount = -1;
    private double[][] cachedChannelSamples; // [channel][pixel]

    private void drawCompressedWaveforms(GraphicsContext g, AxisScrubBar.Bounds bounds, double domain) {
        int pixelCount = (int) Math.max(1, Math.min(bounds.width(), 800));
        var config = editSession.activeConfig.get();
        if (config == null || config.fields().isEmpty()) return;

        // One line per field-channel slot (STATIC fields contribute none).
        var slots = new java.util.ArrayList<SequenceChannel>();
        var maxes = new java.util.ArrayList<Double>();
        var colours = new java.util.ArrayList<Color>();
        for (var field : config.fields()) {
            int count = field.kind().channelCount();
            double fMax = Math.max(Math.abs(field.minAmplitude()), Math.abs(field.maxAmplitude()));
            if (fMax == 0) fMax = 1;
            for (int sub = 0; sub < count; sub++) {
                slots.add(SequenceChannel.ofField(field.name(), sub));
                maxes.add(fMax);
                colours.add(colourForKind(field.kind(), field.name()));
            }
        }

        int currentRevision = editSession.revision.get();
        if (currentRevision != cachedRevision || pixelCount != cachedPixelCount || cachedChannelSamples == null
                || cachedChannelSamples.length != slots.size()) {
            cachedRevision = currentRevision;
            cachedPixelCount = pixelCount;
            cachedChannelSamples = new double[slots.size()][pixelCount];
            var clips = editSession.clips;
            for (int ch = 0; ch < slots.size(); ch++) {
                for (int px = 0; px < pixelCount; px++) {
                    double t = (px / (double) pixelCount) * domain;
                    cachedChannelSamples[ch][px] = ClipEvaluator.evaluateChannel(clips, slots.get(ch), t);
                }
            }
        }

        double xScale = bounds.width() / pixelCount;
        double midY = bounds.y() + bounds.height() / 2;
        double halfH = bounds.height() * 0.35;

        for (int ch = 0; ch < slots.size(); ch++) {
            g.setStroke(colours.get(ch).deriveColor(0, 1, 1, 0.6));
            g.setLineWidth(0.8);
            g.beginPath();

            double fMax = maxes.get(ch);
            for (int px = 0; px < pixelCount; px++) {
                double normalised = fMax > 0 ? cachedChannelSamples[ch][px] / fMax : 0;
                normalised = Math.max(-1, Math.min(1, normalised));
                double y = midY - normalised * halfH;

                if (px == 0) g.moveTo(bounds.x() + px * xScale, y);
                else g.lineTo(bounds.x() + px * xScale, y);
            }
            g.stroke();
        }
    }

    private Color channelColour(SequenceChannel ch) {
        if (ch.isRfGate()) return GATE_COLOUR;
        var field = editSession.fieldForChannel(ch);
        if (field == null) return GX_COLOUR;
        return colourForKind(field.kind(), field.name());
    }

    private static Color colourForKind(AmplitudeKind kind, String fieldName) {
        return switch (kind) {
            case QUADRATURE -> B1_COLOUR;
            case REAL -> (Math.abs(fieldName.hashCode()) % 2 == 0) ? GX_COLOUR : GZ_COLOUR;
            case STATIC -> GX_COLOUR; // unreachable — no channels
        };
    }
}
