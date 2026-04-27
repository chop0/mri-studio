package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.RunContext;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.pane.WaveformCache;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Stateless renderer for the timeline canvas.
 *
 * <p>Paints tracks, clips, axes and interaction overlays into a provided
 * {@link GraphicsContext}. All state it needs comes in as parameters — it
 * never mutates anything and never holds a reference to a canvas.
 */
public final class TimelineRenderer {
    private TimelineRenderer() {}

    // ── Visual constants ─────────────────────────────────────────────────────

    private static final double COLLAPSE_BTN_SIZE = 9;
    private static final double COLLAPSE_BTN_X = 6;
    private static final double CLIP_RADIUS = 3;
    private static final double SPLINE_POINT_RADIUS = 3.5;
    private static final int MAX_WAVEFORM_SAMPLES = 320;

    private static final Color BG = Color.web("#fbfcfd");
    private static final Color BG_ALT = Color.web("#f2f4f8");
    private static final Color LANE_BORDER = Color.web("#dadde3");
    private static final Color LABEL_BG = Color.web("#eef1f5");
    private static final Color LABEL_BORDER = Color.web("#c5cad1");
    private static final Color AXIS_LINE = Color.web("#c5cad1");
    private static final Color AXIS_TEXT = Color.web("#5c6571");
    private static final Color ZERO_LINE = Color.color(0, 0, 0, 0.12);
    private static final Color HW_LINE = Color.color(0.76, 0.14, 0.09, 0.4);
    private static final Color HW_LABEL = Color.color(0.76, 0.14, 0.09, 0.75);
    private static final Color CURSOR = Color.web("#d97706");
    private static final Color SNAP = Color.web("#1d7a3f");
    private static final Color SELECTION_FRAME = Color.web("#0b5cad");
    private static final Color SELECTION_FILL = Color.color(0.043, 0.36, 0.68, 0.10);
    private static final Color KIND_BADGE_FG = Color.web("#3c434e");
    private static final Color ORPHAN_LABEL_BG = Color.web("#fce4e4");
    private static final Color ORPHAN_LABEL_FG = Color.web("#b42318");

    // Output band — read-only signal-trace lanes below the editable tracks.
    // Sim and hardware get their own faint tinted backgrounds so the user can
    // tell at a glance which run produced which row.
    private static final Color OUTPUT_BAND_DIVIDER = Color.web("#a4adb8");
    private static final Color OUTPUT_LABEL_BG = Color.web("#f4f6fa");
    private static final Color OUTPUT_TRACE_SIM = Color.web("#1565c0");
    private static final Color OUTPUT_TRACE_HW = Color.web("#9333ea");
    private static final Color OUTPUT_TINT_SIM = Color.color(0.082, 0.396, 0.753, 0.05);
    private static final Color OUTPUT_TINT_HW = Color.color(0.576, 0.200, 0.918, 0.05);

    private static final Font LABEL_FONT = Font.font("SF Pro Text", FontWeight.BOLD, 10);
    private static final Font AXIS_FONT = Font.font("SF Mono", 9);
    private static final Font CLIP_LABEL_FONT = Font.font("SF Pro Text", FontWeight.MEDIUM, 9);
    private static final Font KIND_BADGE_FONT = Font.font("SF Pro Text", FontWeight.BOLD, 8);

    /** Render the full timeline scene. */
    public static void render(GraphicsContext g,
                              TimelineGeometry geom,
                              SequenceEditSession session,
                              WaveformCache cache,
                              ClipDragState drag,
                              double snapGuideTime,
                              Double cursorTime) {
        g.clearRect(0, 0, geom.width(), geom.height());
        g.setFill(BG);
        g.fillRect(0, 0, geom.width(), geom.height());

        paintTracks(g, geom, session, cache);
        paintOutputBand(g, geom);
        paintOverlays(g, geom, drag);
        paintCursor(g, geom, cursorTime);
        paintSnapGuide(g, geom, snapGuideTime);
        paintTimeAxis(g, geom);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Track rendering
    // ══════════════════════════════════════════════════════════════════════════

    private static void paintTracks(GraphicsContext g, TimelineGeometry geom,
                                    SequenceEditSession session, WaveformCache cache) {
        var tracks = geom.tracks();
        double pL = geom.plotLeft();
        double pW = geom.plotWidth();

        for (int i = 0; i < tracks.size(); i++) {
            var track = tracks.get(i);
            double top = geom.trackTop(i);
            double h = geom.trackHeight(i);
            var src = session.sourceForChannel(track.simChannel());
            boolean orphan = src == null;

            // Row background — alternating, with a faint tint if orphan.
            g.setFill(i % 2 == 0 ? BG : BG_ALT);
            g.fillRect(pL, top, pW, h);
            if (orphan) {
                g.setFill(Color.color(0.76, 0.14, 0.09, 0.04));
                g.fillRect(pL, top, pW, h);
            }

            paintLabel(g, geom, track, src, top, h);

            // Lane divider
            g.setStroke(LANE_BORDER);
            g.setLineWidth(1.0);
            g.strokeLine(0, top + h - 0.5, geom.width(), top + h - 0.5);

            if (geom.isCollapsed(track)) {
                paintCollapsedLane(g, geom, track, session, top, h);
                continue;
            }

            // Zero line
            double mid = geom.trackMid(i);
            g.setStroke(ZERO_LINE);
            g.setLineWidth(0.5);
            g.setLineDashes(3, 3);
            g.strokeLine(pL, mid, pL + pW, mid);
            g.setLineDashes();

            // Hardware limit lines (only when a field backs this output)
            if (!orphan) paintHardwareLimits(g, geom, session, track, i);

            // Clips
            paintTrackClips(g, geom, session, cache, track, i);
        }
    }

    private static void paintLabel(GraphicsContext g, TimelineGeometry geom,
                                   Track track, Object field, double top, double h) {
        double labelW = geom.labelWidth();
        boolean orphan = field == null;

        g.setFill(orphan ? ORPHAN_LABEL_BG : LABEL_BG);
        g.fillRect(0, top, labelW - 1, h);
        g.setStroke(LABEL_BORDER);
        g.setLineWidth(0.5);
        g.strokeLine(labelW - 0.5, top, labelW - 0.5, top + h);

        // Collapse triangle
        boolean collapsed = geom.isCollapsed(track);
        double btnY = top + h / 2 - COLLAPSE_BTN_SIZE / 2;
        g.setFill(orphan ? ORPHAN_LABEL_FG : AXIS_TEXT);
        if (collapsed) {
            g.fillPolygon(
                new double[]{COLLAPSE_BTN_X, COLLAPSE_BTN_X, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE},
                new double[]{btnY, btnY + COLLAPSE_BTN_SIZE, btnY + COLLAPSE_BTN_SIZE / 2}, 3);
        } else {
            g.fillPolygon(
                new double[]{COLLAPSE_BTN_X, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE / 2},
                new double[]{btnY, btnY, btnY + COLLAPSE_BTN_SIZE}, 3);
        }

        // Kind badge (R / G). Every source is single-channel post-QUADRATURE;
        // quadrature drives are composed from two REAL sources + a Modulator
        // block, so sub-index distinctions live on the Modulator, not here.
        String badge = null;
        if (field instanceof ax.xz.mri.model.circuit.CircuitComponent.VoltageSource src) {
            if (src.kind() == AmplitudeKind.REAL) badge = "R";
            else if (src.kind() == AmplitudeKind.GATE) badge = "G";
        }
        if (badge != null) {
            double badgeX = labelW - 16;
            double badgeY = top + h / 2 - 6;
            g.setFill(Color.color(0, 0, 0, 0.06));
            g.fillRoundRect(badgeX, badgeY, 12, 12, 3, 3);
            g.setFill(KIND_BADGE_FG);
            g.setFont(KIND_BADGE_FONT);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(badge, badgeX + 6, badgeY + 9);
            g.setTextAlign(TextAlignment.LEFT);
        }

        // Label text, right-aligned before the badge.
        g.setFill(orphan ? ORPHAN_LABEL_FG : AXIS_TEXT);
        g.setFont(LABEL_FONT);
        g.setTextAlign(TextAlignment.RIGHT);
        double labelRight = (badge != null) ? labelW - 20 : labelW - 6;
        g.fillText(track.name(), labelRight, top + h / 2 + 3);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static void paintHardwareLimits(GraphicsContext g, TimelineGeometry geom,
                                            SequenceEditSession session,
                                            Track track, int trackIndex) {
        double hwMax = channelHardwareMax(session, track.simChannel());
        if (hwMax <= 0) return;
        double displayMax = hwMax * 1.15;
        double yPos = geom.valueToY(trackIndex, hwMax, displayMax);
        double yNeg = geom.valueToY(trackIndex, -hwMax, displayMax);
        double pL = geom.plotLeft();
        double pW = geom.plotWidth();

        g.setStroke(HW_LINE);
        g.setLineWidth(0.8);
        g.setLineDashes(5, 4);
        g.strokeLine(pL, yPos, pL + pW, yPos);
        g.strokeLine(pL, yNeg, pL + pW, yNeg);
        g.setLineDashes();

        g.setFill(HW_LABEL);
        g.setFont(AXIS_FONT);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(formatAxisValue(session, track.simChannel(), hwMax), pL + 4, yPos - 2);
    }

    private static void paintCollapsedLane(GraphicsContext g, TimelineGeometry geom,
                                           Track track, SequenceEditSession session,
                                           double top, double h) {
        Color base = ChannelPalette.colourFor(session, track.simChannel());
        var trackClips = session.clipsOnTrack(track.id());
        for (var clip : trackClips) {
            if (clip.endTime() < geom.viewStart() || clip.startTime() > geom.viewEnd()) continue;
            double x1 = geom.timeToX(clip.startTime());
            double x2 = geom.timeToX(clip.endTime());
            boolean sel = session.isSelected(clip.id());
            g.setFill(ChannelPalette.tint(base, sel ? 0.55 : 0.3));
            g.fillRect(x1, top + 2, Math.max(1, x2 - x1), h - 4);
        }
    }

    private static void paintTrackClips(GraphicsContext g, TimelineGeometry geom,
                                        SequenceEditSession session, WaveformCache cache,
                                        Track track, int trackIndex) {
        var trackClips = session.clipsOnTrack(track.id());
        double top = geom.trackTop(trackIndex);
        double h = geom.trackHeight(trackIndex);
        double displayMax = channelDisplayMax(session, track.simChannel());
        Color base = ChannelPalette.colourFor(session, track.simChannel());

        for (var clip : trackClips) {
            if (clip.endTime() < geom.viewStart() || clip.startTime() > geom.viewEnd()) continue;
            paintClip(g, geom, session, cache, clip, trackIndex, top, h, displayMax, base);
        }
    }

    private static void paintClip(GraphicsContext g, TimelineGeometry geom,
                                  SequenceEditSession session, WaveformCache cache,
                                  SignalClip clip, int trackIndex, double top, double h,
                                  double displayMax, Color base) {
        double x1 = geom.timeToX(clip.startTime());
        double x2 = geom.timeToX(clip.endTime());
        double w = Math.max(1, x2 - x1);
        boolean selected = session.isSelected(clip.id());

        var gradient = new LinearGradient(
            0, 0, 0, 1, true, null,
            new Stop(0, ChannelPalette.tint(base, selected ? 0.30 : 0.18)),
            new Stop(1, ChannelPalette.tint(base, selected ? 0.20 : 0.10))
        );
        g.setFill(gradient);
        g.fillRoundRect(x1, top + 2, w, h - 4, CLIP_RADIUS, CLIP_RADIUS);

        if (selected) {
            g.setStroke(SELECTION_FRAME);
            g.setLineWidth(1.4);
        } else {
            g.setStroke(ChannelPalette.tint(base, 0.55));
            g.setLineWidth(0.9);
        }
        g.strokeRoundRect(x1, top + 2, w, h - 4, CLIP_RADIUS, CLIP_RADIUS);

        g.save();
        g.beginPath();
        g.rect(x1, top + 2, w, h - 4);
        g.clip();
        paintClipWaveform(g, geom, cache, clip, trackIndex, x1, x2, displayMax, base);
        g.restore();

        if (w > 34) {
            g.setFill(ChannelPalette.tint(base, 0.85));
            g.setFont(CLIP_LABEL_FONT);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(clip.shape().displayName(), x1 + 5, top + 13);
        }

        if (selected && clip.shape() instanceof ClipShape.Spline spline) {
            g.setLineWidth(1.2);
            for (int s = 0; s < spline.points().size(); s++) {
                var pt = spline.points().get(s);
                double px = geom.timeToX(clip.startTime() + pt.t() * clip.duration());
                double py = geom.valueToY(trackIndex, pt.value() * clip.amplitude(), displayMax);
                g.setFill(Color.WHITE);
                g.fillOval(px - SPLINE_POINT_RADIUS, py - SPLINE_POINT_RADIUS,
                    SPLINE_POINT_RADIUS * 2, SPLINE_POINT_RADIUS * 2);
                g.setStroke(base);
                g.strokeOval(px - SPLINE_POINT_RADIUS, py - SPLINE_POINT_RADIUS,
                    SPLINE_POINT_RADIUS * 2, SPLINE_POINT_RADIUS * 2);
            }
        }

        if (selected) {
            g.setStroke(SELECTION_FRAME);
            g.setLineWidth(2);
            g.strokeLine(x1, top + 6, x1, top + h - 6);
            g.strokeLine(x2, top + 6, x2, top + h - 6);
        }
    }

    private static void paintClipWaveform(GraphicsContext g, TimelineGeometry geom,
                                          WaveformCache cache, SignalClip clip, int trackIndex,
                                          double x1, double x2, double displayMax, Color base) {
        double w = x2 - x1;
        int samples = (int) Math.max(2, Math.min(w, MAX_WAVEFORM_SAMPLES));
        double[] values = cache.getOrCompute(clip, samples);

        g.setStroke(ChannelPalette.tint(base, 0.85));
        g.setLineWidth(1.2);
        g.beginPath();
        for (int i = 0; i <= samples; i++) {
            double x = x1 + ((double) i / samples) * w;
            double y = geom.valueToY(trackIndex, values[i], displayMax);
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Output band (read-only probe traces shown beneath the editable tracks)
    // ══════════════════════════════════════════════════════════════════════════

    private static void paintOutputBand(GraphicsContext g, TimelineGeometry geom) {
        var rows = geom.outputRows();
        if (rows.isEmpty()) return;

        double bandTop = geom.outputBandTop();
        double pL = geom.plotLeft();
        double pW = geom.plotWidth();
        double labelW = geom.labelWidth();

        // A single thicker divider line separates the editable area from the
        // read-only band — visually unmistakable that everything below it is
        // not editable.
        g.setStroke(OUTPUT_BAND_DIVIDER);
        g.setLineWidth(1.2);
        g.strokeLine(0, bandTop - 0.5, geom.width(), bandTop - 0.5);
        g.setLineWidth(1.0);

        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            double top = geom.outputRowTop(i);
            double h = geom.outputRowHeight();
            paintOutputRow(g, row, top, h, pL, pW, labelW);
            // Lane separator between consecutive output rows.
            g.setStroke(LANE_BORDER);
            g.setLineWidth(0.5);
            g.strokeLine(0, top + h - 0.5, geom.width(), top + h - 0.5);
        }
    }

    private static void paintOutputRow(GraphicsContext g, OutputRow row,
                                       double top, double h,
                                       double plotLeft, double plotWidth, double labelW) {
        boolean sim = row.context() == RunContext.SIMULATION;
        Color tint = sim ? OUTPUT_TINT_SIM : OUTPUT_TINT_HW;
        Color trace = sim ? OUTPUT_TRACE_SIM : OUTPUT_TRACE_HW;

        // Lane background — alternating sim/hw faint tint.
        g.setFill(tint);
        g.fillRect(plotLeft, top, plotWidth, h);

        // Label column with a context badge ("S" / "H") + probe name.
        g.setFill(OUTPUT_LABEL_BG);
        g.fillRect(0, top, labelW - 1, h);
        g.setStroke(LABEL_BORDER);
        g.setLineWidth(0.5);
        g.strokeLine(labelW - 0.5, top, labelW - 0.5, top + h);

        double badgeX = 4;
        double badgeY = top + h / 2 - 6;
        g.setFill(trace);
        g.fillRoundRect(badgeX, badgeY, 12, 12, 3, 3);
        g.setFill(Color.WHITE);
        g.setFont(KIND_BADGE_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(sim ? "S" : "H", badgeX + 6, badgeY + 9);
        g.setTextAlign(TextAlignment.LEFT);

        g.setFill(AXIS_TEXT);
        g.setFont(LABEL_FONT);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(row.label(), labelW - 6, top + h / 2 + 3);
        g.setTextAlign(TextAlignment.LEFT);

        // Zero line.
        double mid = top + h / 2;
        g.setStroke(ZERO_LINE);
        g.setLineDashes(2, 3);
        g.strokeLine(plotLeft, mid, plotLeft + plotWidth, mid);
        g.setLineDashes();

        paintTraceMagnitude(g, row.trace(), top, h, plotLeft, plotWidth, trace);
    }

    /**
     * Render the magnitude envelope of a {@link SignalTrace} into a row's
     * vertical band. Plots √(re²+im²) so a complex demodulated probe shows up
     * as a positive envelope above the centreline. The full trace is mapped
     * across the row's plot rect — there's no time-clipping against the editor
     * viewport, because output rows track the run's full timeline regardless
     * of the user's current zoom.
     */
    private static void paintTraceMagnitude(GraphicsContext g, SignalTrace trace,
                                            double top, double h,
                                            double plotLeft, double plotWidth, Color stroke) {
        var pts = trace.points();
        if (pts.isEmpty()) return;

        double maxMag = 0;
        for (var p : pts) {
            double m = Math.hypot(p.real(), p.imag());
            if (m > maxMag) maxMag = m;
        }
        if (maxMag <= 0) maxMag = 1;

        double padding = 4;
        double laneTop = top + padding;
        double laneBottom = top + h - padding;
        double laneHeight = laneBottom - laneTop;

        double tStart = pts.getFirst().tMicros();
        double tEnd = pts.getLast().tMicros();
        double tSpan = Math.max(1e-9, tEnd - tStart);

        g.setStroke(stroke);
        g.setLineWidth(1.1);
        g.beginPath();
        for (int i = 0; i < pts.size(); i++) {
            var p = pts.get(i);
            double mag = Math.hypot(p.real(), p.imag());
            double x = plotLeft + (p.tMicros() - tStart) / tSpan * plotWidth;
            // Map [0..maxMag] to [laneBottom..laneTop] — magnitude is non-negative.
            double y = laneBottom - (mag / maxMag) * laneHeight;
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Overlays (selection, create preview, snap guide, cursor)
    // ══════════════════════════════════════════════════════════════════════════

    private static void paintOverlays(GraphicsContext g, TimelineGeometry geom, ClipDragState drag) {
        switch (drag) {
            case ClipDragState.RubberBand r -> paintRubberBand(g, r);
            case ClipDragState.CreateClip c -> paintCreateClipPreview(g, geom, c);
            default -> { /* nothing */ }
        }
    }

    private static void paintRubberBand(GraphicsContext g, ClipDragState.RubberBand r) {
        double rx = Math.min(r.startX(), r.endX());
        double ry = Math.min(r.startY(), r.endY());
        double rw = Math.abs(r.endX() - r.startX());
        double rh = Math.abs(r.endY() - r.startY());
        g.setFill(SELECTION_FILL);
        g.fillRect(rx, ry, rw, rh);
        g.setStroke(SELECTION_FRAME);
        g.setLineWidth(1);
        g.strokeRect(rx, ry, rw, rh);
    }

    private static void paintCreateClipPreview(GraphicsContext g, TimelineGeometry geom,
                                               ClipDragState.CreateClip c) {
        int ti = -1;
        for (int i = 0; i < geom.tracks().size(); i++) {
            if (geom.tracks().get(i).id().equals(c.trackId())) { ti = i; break; }
        }
        if (ti < 0) return;

        double startX = geom.timeToX(c.startTimeMicros());
        double endX = c.currentX();
        double top = geom.trackTop(ti);
        double h = geom.trackHeight(ti);
        double x1 = Math.min(startX, endX);
        double x2 = Math.max(startX, endX);

        g.setFill(Color.color(0.043, 0.36, 0.68, 0.10));
        g.fillRoundRect(x1, top + 2, x2 - x1, h - 4, CLIP_RADIUS, CLIP_RADIUS);
        g.setStroke(SELECTION_FRAME);
        g.setLineWidth(1);
        g.setLineDashes(4, 3);
        g.strokeRoundRect(x1, top + 2, x2 - x1, h - 4, CLIP_RADIUS, CLIP_RADIUS);
        g.setLineDashes();

        g.setFill(SELECTION_FRAME);
        g.setFont(AXIS_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(kindLabel(c.kind()), (x1 + x2) / 2, top + 12);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static String kindLabel(ClipKind kind) {
        return kind == null ? "" : kind.displayName();
    }

    private static void paintCursor(GraphicsContext g, TimelineGeometry geom, Double cursorTime) {
        if (cursorTime == null) return;
        double cx = geom.timeToX(cursorTime);
        if (cx < geom.plotLeft() || cx > geom.plotRight()) return;
        g.setStroke(CURSOR);
        g.setLineWidth(1.5);
        g.strokeLine(cx, 0, cx, geom.plotHeight());
    }

    private static void paintSnapGuide(GraphicsContext g, TimelineGeometry geom, double snapTime) {
        if (Double.isNaN(snapTime)) return;
        double sx = geom.timeToX(snapTime);
        g.setStroke(SNAP);
        g.setLineWidth(1);
        g.setLineDashes(4, 3);
        g.strokeLine(sx, 0, sx, geom.plotHeight());
        g.setLineDashes();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Time axis
    // ══════════════════════════════════════════════════════════════════════════

    private static void paintTimeAxis(GraphicsContext g, TimelineGeometry geom) {
        double pL = geom.plotLeft();
        double pW = geom.plotWidth();
        double axisY = geom.height() - geom.bottomPad();
        double vS = geom.viewStart();
        double vE = geom.viewEnd();
        double span = vE - vS;

        g.setStroke(AXIS_LINE);
        g.setLineWidth(0.5);
        g.strokeLine(pL, axisY, pL + pW, axisY);

        double rawStep = span / 8;
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, rawStep))));
        double normalised = rawStep / magnitude;
        double tickStep;
        if (normalised <= 2) tickStep = 2 * magnitude;
        else if (normalised <= 5) tickStep = 5 * magnitude;
        else tickStep = 10 * magnitude;

        double firstTick = Math.ceil(vS / tickStep) * tickStep;
        g.setFill(AXIS_TEXT);
        g.setFont(AXIS_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        for (double t = firstTick; t <= vE; t += tickStep) {
            double x = geom.timeToX(t);
            g.strokeLine(x, axisY, x, axisY + 3);
            g.fillText(formatTimeTick(t), x, axisY + 12);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static String formatTimeTick(double micros) {
        if (Math.abs(micros) >= 1000) return String.format("%.1f ms", micros / 1000);
        return String.format("%.0f μs", micros);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Channel amplitude helpers (shared with interaction for hit-testing)
    // ══════════════════════════════════════════════════════════════════════════

    /** Half-span of the Y axis for a channel, with 15 % headroom above hardware limit. */
    public static double channelDisplayMax(SequenceEditSession session, SequenceChannel channel) {
        double hw = channelHardwareMax(session, channel);
        return hw * 1.15;
    }

    /** Hardware limit for a channel — the peak of the voltage source's amplitude range. */
    public static double channelHardwareMax(SequenceEditSession session, SequenceChannel channel) {
        var src = session.sourceForChannel(channel);
        if (src != null) {
            double m = Math.max(Math.abs(src.minAmplitude()), Math.abs(src.maxAmplitude()));
            if (m > 0) return m;
        }
        return 1.0;
    }

    /** Format an amplitude as a physical value with SI prefix, for axis labels.
     *  Eigenfields hold shape only — the displayed value is the raw source
     *  amplitude tagged with the eigenfield's units label. The physical
     *  magnitude that the simulator sees is multiplied by each coil's
     *  sensitivity (T/A); the timeline axis intentionally stays in source
     *  units so the user reads the controls they edit, not the cascaded
     *  product of every downstream coil. */
    public static String formatAxisValue(SequenceEditSession session, SequenceChannel channel, double amplitude) {
        var ef = session.eigenfieldForChannel(channel);
        String units = ef != null ? ef.units() : "";
        return formatWithUnits(amplitude, units);
    }

    private static String formatWithUnits(double value, String units) {
        if (units == null || units.isEmpty()) return String.format("%.3g", value);
        double abs = Math.abs(value);
        if (abs == 0) return "0 " + units;
        if (abs >= 1e9)  return String.format("%.2f G%s", value / 1e9, units);
        if (abs >= 1e6)  return String.format("%.2f M%s", value / 1e6, units);
        if (abs >= 1e3)  return String.format("%.2f k%s", value / 1e3, units);
        if (abs >= 1)    return String.format("%.2f %s",  value,       units);
        if (abs >= 1e-3) return String.format("%.2f m%s", value * 1e3, units);
        if (abs >= 1e-6) return String.format("%.2f \u03BC%s", value * 1e6, units);
        if (abs >= 1e-9) return String.format("%.2f n%s", value * 1e9, units);
        return String.format("%.3g %s", value, units);
    }
}
