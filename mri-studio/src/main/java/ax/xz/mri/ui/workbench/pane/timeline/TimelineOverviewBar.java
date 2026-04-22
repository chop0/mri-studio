package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipEvaluator;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.ViewportViewModel;
import ax.xz.mri.ui.workbench.pane.AxisScrubBar;
import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Miniature overview of the sequence beneath the main track canvas. Shows the
 * clip layout in compressed form, a live mini-waveform per field-channel
 * slot, and a draggable viewport handle synced with the global
 * {@link ViewportViewModel} so zooming/panning stays in lockstep with
 * downstream analysis panes.
 */
public final class TimelineOverviewBar extends ResizableCanvas {

    private static final double LEFT_PAD = 78;
    private static final double RIGHT_PAD = 8;
    private static final Color BG = Color.web("#f2f4f8");
    private static final Color LABEL_BG = Color.web("#eef1f5");
    private static final Color LABEL_BORDER = Color.web("#c5cad1");
    private static final Color LABEL_TEXT = Color.web("#5c6571");
    private static final Color CURSOR = Color.web("#d97706");
    private static final Font LABEL_FONT = Font.font("SF Pro Text", 10);

    private final SequenceEditSession session;
    private final ViewportViewModel viewport;
    private final AxisScrubBar.Interaction interaction;
    private final ViewportSync sync;
    private final AnimationTimer timer;
    private boolean dirty = true;

    // Cached per-channel waveform samples — rebuilt when revision or pixel count changes.
    private int cachedRevision = -1;
    private int cachedPixelCount = -1;
    private double[][] cachedChannelSamples;

    public TimelineOverviewBar(SequenceEditSession session, ViewportViewModel viewport) {
        this.session = session;
        this.viewport = viewport;
        getStyleClass().add("timeline-overview");

        this.interaction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.HORIZONTAL, new OverviewWindowModel(session));

        // Bidirectional viewport sync (editor ↔ global). Also mirrors into the
        // analysis window (tS/tE) so downstream plots follow.
        this.sync = new ViewportSync();
        sync.bindBidirectional(session.viewStart, viewport.vS);
        sync.bindBidirectional(session.viewEnd, viewport.vE);
        sync.bindBidirectional(session.viewStart, viewport.tS);
        sync.bindBidirectional(session.viewEnd, viewport.tE);

        setOnResized(this::markDirty);
        session.revision.addListener((obs, o, n) -> markDirty());
        session.viewStart.addListener((obs, o, n) -> markDirty());
        session.viewEnd.addListener((obs, o, n) -> markDirty());
        session.totalDuration.addListener((obs, o, n) -> markDirty());
        viewport.tC.addListener((obs, o, n) -> markDirty());

        setOnMousePressed(this::onPress);
        setOnMouseDragged(this::onDragged);
        setOnMouseReleased(e -> interaction.handleRelease());
        setOnScroll(this::onScrolled);

        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (dirty) { dirty = false; paint(); }
            }
        };
        timer.start();
    }

    public void dispose() { timer.stop(); }

    private void markDirty() { dirty = true; }

    private AxisScrubBar.Bounds barBounds() {
        return new AxisScrubBar.Bounds(LEFT_PAD, 0, getWidth() - LEFT_PAD - RIGHT_PAD, getHeight());
    }

    private void onPress(MouseEvent e) { interaction.handlePress(barBounds(), e); markDirty(); }
    private void onDragged(MouseEvent e) { interaction.handleDrag(barBounds(), e); markDirty(); }
    private void onScrolled(ScrollEvent e) { interaction.handleScroll(barBounds(), e); markDirty(); }

    // ══════════════════════════════════════════════════════════════════════════
    // Rendering
    // ══════════════════════════════════════════════════════════════════════════

    private void paint() {
        double w = getWidth();
        double h = getHeight();
        var g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        // Label column
        g.setFill(LABEL_BG);
        g.fillRect(0, 0, LEFT_PAD - 1, h);
        g.setStroke(LABEL_BORDER);
        g.setLineWidth(0.5);
        g.strokeLine(LEFT_PAD - 0.5, 0, LEFT_PAD - 0.5, h);
        g.setFill(LABEL_TEXT);
        g.setFont(LABEL_FONT);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("Overview", LEFT_PAD - 6, h / 2 + 3);
        g.setTextAlign(TextAlignment.LEFT);

        var bounds = barBounds();
        double domain = Math.max(1, session.totalDuration.get());

        // Spans: clip extents, coloured per channel
        var spans = new ArrayList<AxisScrubBar.Span>();
        for (var clip : session.clips) {
            Color colour = ChannelPalette.colourFor(session, clip.channel());
            spans.add(new AxisScrubBar.Span(clip.startTime(), clip.endTime(), colour, 0.14));
        }

        var markers = List.of(new AxisScrubBar.Marker(viewport.tC.get(), CURSOR));
        var spec = AxisScrubBar.Spec.horizontal(
            0, domain,
            session.viewStart.get(), session.viewEnd.get(),
            spans, markers);
        AxisScrubBar.draw(g, bounds, spec);

        paintCompressedWaveforms(g, bounds, domain);
    }

    private void paintCompressedWaveforms(GraphicsContext g, AxisScrubBar.Bounds bounds, double domain) {
        int pixelCount = (int) Math.max(1, Math.min(bounds.width(), 900));
        var config = session.activeConfig.get();
        if (config == null || config.fields().isEmpty()) return;

        // Build channel-slot list matching the config's lane order
        var slots = new ArrayList<SequenceChannel>();
        var maxes = new ArrayList<Double>();
        var colours = new ArrayList<Color>();
        for (var field : config.fields()) {
            int count = field.kind().channelCount();
            double fMax = Math.max(Math.abs(field.minAmplitude()), Math.abs(field.maxAmplitude()));
            if (fMax == 0) fMax = 1;
            for (int sub = 0; sub < count; sub++) {
                slots.add(SequenceChannel.ofField(field.name(), sub));
                maxes.add(fMax);
                colours.add(ChannelPalette.colourFor(field.kind(), field.name()));
            }
        }

        int rev = session.revision.get();
        if (rev != cachedRevision || pixelCount != cachedPixelCount
                || cachedChannelSamples == null || cachedChannelSamples.length != slots.size()) {
            cachedRevision = rev;
            cachedPixelCount = pixelCount;
            cachedChannelSamples = new double[slots.size()][pixelCount];
            var clips = session.clips;
            for (int ch = 0; ch < slots.size(); ch++) {
                for (int px = 0; px < pixelCount; px++) {
                    double t = (px / (double) pixelCount) * domain;
                    cachedChannelSamples[ch][px] = ClipEvaluator.evaluateChannel(clips, slots.get(ch), t);
                }
            }
        }

        double xScale = bounds.width() / pixelCount;
        double midY = bounds.y() + bounds.height() / 2;
        double halfH = bounds.height() * 0.38;

        for (int ch = 0; ch < slots.size(); ch++) {
            g.setStroke(ChannelPalette.tint(colours.get(ch), 0.65));
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

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private record OverviewWindowModel(SequenceEditSession session) implements AxisScrubBar.WindowModel {
        @Override public double domainStart() { return 0; }
        @Override public double domainEnd()   { return Math.max(1, session.totalDuration.get()); }
        @Override public double windowStart() { return session.viewStart.get(); }
        @Override public double windowEnd()   { return session.viewEnd.get(); }
        @Override public void setWindow(double start, double end) {
            session.viewStart.set(start);
            session.viewEnd.set(end);
        }
        @Override public void zoomAround(double anchor, double factor) {
            session.zoomViewAround(anchor, factor);
        }
        @Override public void resetWindow() { session.fitView(); }
    }

    /**
     * One-shot helper for bidirectionally binding pairs of
     * {@link DoubleProperty}s without the manual {@code syncing} flag pattern.
     */
    private static final class ViewportSync {
        private boolean syncing;

        void bindBidirectional(DoubleProperty a, DoubleProperty b) {
            a.addListener((obs, o, n) -> {
                if (syncing) return;
                syncing = true;
                try { b.set(n.doubleValue()); }
                finally { syncing = false; }
            });
            b.addListener((obs, o, n) -> {
                if (syncing) return;
                syncing = true;
                try { a.set(n.doubleValue()); }
                finally { syncing = false; }
            });
        }
    }
}
