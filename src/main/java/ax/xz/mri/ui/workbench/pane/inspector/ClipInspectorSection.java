package ax.xz.mri.ui.workbench.pane.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import ax.xz.mri.ui.workbench.pane.config.SegmentedControl;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Clip-editing panel for the {@link ax.xz.mri.ui.workbench.pane.InspectorPane}.
 *
 * <p>A single instance owns one clip's UI and survives across
 * {@link SequenceEditSession#revision revision bumps} — instead of rebuilding
 * the UI on every keystroke (the old {@code suppressRefresh} pattern) it
 * pushes fresh values into the existing fields via
 * {@link NumberField#setValueQuiet(double)}, preserving input focus and
 * caret position. The UI only rebuilds when the user selects a different
 * clip or changes the shape type.
 *
 * <h3>Layout</h3>
 * <ul>
 *   <li>Channel + shape header (combo)</li>
 *   <li>Timing block — start, duration, amplitude</li>
 *   <li>Shape-specific parameters — one {@link NumberField} per typed param</li>
 *   <li>Action row — Delete, Duplicate</li>
 * </ul>
 */
public final class ClipInspectorSection {

    private final SequenceEditSession session;
    private final String clipId;
    private final VBox root = new VBox(8);
    private final List<Consumer<SignalClip>> pullers = new ArrayList<>();
    private ClipKind builtForKind; // the shape kind last used to build the param UI

    /** Observable points-of-interest; rotations are shown per entry for RF clips. May be {@code null}. */
    private final ObservableList<IsochromatEntry> poiEntries;
    /** Listener on {@link #poiEntries} so trajectory refreshes repaint the rotation table. */
    private final ListChangeListener<IsochromatEntry> poiListener;
    private Runnable poiRefresher;

    public ClipInspectorSection(SequenceEditSession session, SignalClip initialClip) {
        this(session, initialClip, null);
    }

    public ClipInspectorSection(SequenceEditSession session, SignalClip initialClip,
                                 ObservableList<IsochromatEntry> poiEntries) {
        this.session = session;
        this.clipId = initialClip.id();
        this.builtForKind = initialClip.shape().kind();
        this.poiEntries = poiEntries;
        this.poiListener = poiEntries == null ? null : change -> {
            if (poiRefresher != null) poiRefresher.run();
        };
        if (poiEntries != null) poiEntries.addListener(poiListener);
        build(initialClip);
    }

    public Node view() { return root; }

    /** Identity of the clip this section is bound to. */
    public String clipId() { return clipId; }

    /** Detach listeners attached by this section. Idempotent. */
    public void dispose() {
        if (poiEntries != null && poiListener != null) {
            poiEntries.removeListener(poiListener);
        }
    }

    /**
     * Push the latest values from the session into the existing fields. If the
     * shape kind has changed, rebuild the param section so the typed fields
     * match the new shape variant.
     */
    public void refresh() {
        var clip = session.findClip(clipId);
        if (clip == null) return;
        if (clip.shape().kind() != builtForKind) {
            builtForKind = clip.shape().kind();
            build(clip);
            return;
        }
        for (var p : pullers) p.accept(clip);
        if (poiRefresher != null) poiRefresher.run();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI construction
    // ══════════════════════════════════════════════════════════════════════════

    private void build(SignalClip clip) {
        root.getChildren().clear();
        pullers.clear();
        poiRefresher = null;

        root.getChildren().addAll(
            buildHeader(clip),
            buildSelectionBadge(),
            buildTrackPicker(clip),
            buildTimingRows(clip),
            buildShapeParams(clip),
            buildPoiRotationSection(),
            new Separator(),
            buildActionRow()
        );

        // Initial values
        refresh();
    }

    private Node buildHeader(SignalClip clip) {
        // Track name (editable via context menu elsewhere), with an arrow to its shape kind.
        var trackLabel = new Label();
        trackLabel.getStyleClass().add("clip-inspector-channel");
        pullers.add(c -> {
            var track = session.findTrack(c.trackId());
            trackLabel.setText(track != null ? track.name() : "\u2014");
        });

        var shapeCombo = new ComboBox<ClipKind>();
        shapeCombo.getItems().addAll(ClipKind.values());
        shapeCombo.setValue(clip.shape().kind());
        shapeCombo.setOnAction(e -> {
            var selected = shapeCombo.getValue();
            if (selected != null) session.changeClipKind(clipId, selected);
        });
        pullers.add(c -> {
            if (c != null && shapeCombo.getValue() != c.shape().kind()) {
                var old = shapeCombo.getOnAction();
                shapeCombo.setOnAction(null);
                shapeCombo.setValue(c.shape().kind());
                shapeCombo.setOnAction(old);
            }
        });

        var arrow = new Label("\u2192");
        arrow.getStyleClass().add("clip-inspector-arrow");

        var row = new HBox(8, trackLabel, arrow, shapeCombo);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node buildTrackPicker(SignalClip clip) {
        var tracks = session.tracks;
        var combo = new ComboBox<Track>();
        combo.setPrefWidth(200);
        combo.getItems().setAll(tracks);
        combo.setCellFactory(lv -> trackCell());
        combo.setButtonCell(trackCell());
        combo.setValue(session.findTrack(clip.trackId()));
        combo.setOnAction(e -> {
            var t = combo.getValue();
            if (t != null) session.changeClipTrack(clipId, t.id());
        });
        // Keep combo in sync when the session's track list or the clip's track id changes.
        Runnable sync = () -> {
            var current = session.findClip(clipId);
            if (current == null) return;
            combo.getItems().setAll(session.tracks);
            var old = combo.getOnAction();
            combo.setOnAction(null);
            combo.setValue(session.findTrack(current.trackId()));
            combo.setOnAction(old);
        };
        session.tracks.addListener((javafx.collections.ListChangeListener<Track>) c -> sync.run());
        pullers.add(c -> sync.run());

        return row("Track", combo);
    }

    private javafx.scene.control.ListCell<Track> trackCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(Track item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        };
    }

    private Node buildSelectionBadge() {
        var label = new Label();
        label.getStyleClass().add("clip-inspector-meta");
        Runnable update = () -> {
            int count = session.selectedClipIds.size();
            label.setText(count > 1 ? count + " clips selected" : "");
            label.setVisible(count > 1);
            label.setManaged(count > 1);
        };
        update.run();
        session.selectedClipIds.addListener((javafx.collections.SetChangeListener<String>) c -> update.run());
        return label;
    }

    // ── Timing rows (start, duration, amplitude) ────────────────────────────

    private Node buildTimingRows(SignalClip clip) {
        var grid = new VBox(4);

        // Start time
        var start = nf().range(0, session.totalDuration.get()).step(1).decimals(1).unit("μs");
        start.setValue(clip.startTime());
        start.valueProperty().addListener((obs, o, n) -> {
            if (n != null) session.moveClip(clipId, n.doubleValue());
        });
        pullers.add(c -> start.setValueQuiet(c.startTime()));
        grid.getChildren().add(row("Start", start));

        // Duration
        var dur = nf().range(session.dt.get(), session.totalDuration.get()).step(1).decimals(1).unit("μs");
        dur.setValue(clip.duration());
        dur.valueProperty().addListener((obs, o, n) -> {
            if (n != null) session.resizeClip(clipId, n.doubleValue());
        });
        pullers.add(c -> dur.setValueQuiet(c.duration()));
        grid.getChildren().add(row("Duration", dur));

        // Amplitude — with physical-peak readout driven by eigenfield metadata
        var track = session.findTrack(clip.trackId());
        var ef = track != null ? session.eigenfieldForChannel(track.outputChannel()) : null;
        String units = ef != null ? ef.units() : "";
        var amp = nf().range(-1e6, 1e6).step(0.1).scientific().unit(units.isEmpty() ? "" : units);
        amp.setValue(clip.amplitude());
        amp.valueProperty().addListener((obs, o, n) -> {
            if (n != null) session.setClipAmplitude(clipId, n.doubleValue());
        });
        pullers.add(c -> amp.setValueQuiet(c.amplitude()));

        var peakLabel = new Label();
        peakLabel.getStyleClass().add("clip-inspector-hint");
        pullers.add(c -> peakLabel.setText(formatPeak(c, ef)));

        var row = new HBox(6, labelCol("Amplitude"), amp, peakLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        grid.getChildren().add(row);

        return grid;
    }

    // ── Shape-specific params ───────────────────────────────────────────────

    private Node buildShapeParams(SignalClip clip) {
        var box = new VBox(4);
        var title = new Label("Shape parameters");
        title.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(title);

        switch (clip.shape()) {
            case ClipShape.Constant ignored ->
                box.getChildren().add(new Label("— no parameters"));

            case ClipShape.Sinc sinc -> {
                addSinc(box, sinc);
            }
            case ClipShape.Trapezoid trap -> {
                addTrapezoid(box, clip.duration(), trap);
            }
            case ClipShape.Gaussian gauss -> {
                addGaussian(box, clip.duration(), gauss);
            }
            case ClipShape.Triangle tri -> {
                addTriangle(box, tri);
            }
            case ClipShape.Sine sine -> {
                addSine(box, sine);
            }
            case ClipShape.Spline spline -> {
                var lbl = new Label();
                pullers.add(c -> {
                    if (c.shape() instanceof ClipShape.Spline s)
                        lbl.setText(s.points().size() + " control points");
                });
                box.getChildren().add(lbl);
            }
        }
        return box;
    }

    private void addSinc(VBox box, ClipShape.Sinc initial) {
        var bw = nf().range(10, 1_000_000).step(100).decimals(1).unit("Hz");
        bw.setValue(initial.bandwidthHz());
        bw.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sinc s)) return;
            session.setClipShape(clipId,
                new ClipShape.Sinc(n.doubleValue(), s.centerOffset(), s.windowFactor()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sinc s) bw.setValueQuiet(s.bandwidthHz()); });
        box.getChildren().add(row("Bandwidth", bw));

        var offset = nf().range(-1e6, 1e6).step(1).decimals(1).unit("μs");
        offset.setValue(initial.centerOffset());
        offset.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sinc s)) return;
            session.setClipShape(clipId,
                new ClipShape.Sinc(s.bandwidthHz(), n.doubleValue(), s.windowFactor()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sinc s) offset.setValueQuiet(s.centerOffset()); });
        box.getChildren().add(row("Center offset", offset));

        var window = nf().range(0.1, 5).step(0.1).decimals(2);
        window.setValue(initial.windowFactor());
        window.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sinc s)) return;
            session.setClipShape(clipId,
                new ClipShape.Sinc(s.bandwidthHz(), s.centerOffset(), n.doubleValue()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sinc s) window.setValueQuiet(s.windowFactor()); });
        box.getChildren().add(row("Window factor", window));
    }

    private void addTrapezoid(VBox box, double duration, ClipShape.Trapezoid initial) {
        var rise = nf().range(0, duration).step(1).decimals(1).unit("μs");
        rise.setValue(initial.riseTime());
        rise.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Trapezoid t)) return;
            session.setClipShape(clipId, new ClipShape.Trapezoid(n.doubleValue(), t.flatTime()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Trapezoid t) rise.setValueQuiet(t.riseTime()); });
        box.getChildren().add(row("Rise time", rise));

        var flat = nf().range(0, duration).step(1).decimals(1).unit("μs");
        flat.setValue(initial.flatTime());
        flat.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Trapezoid t)) return;
            session.setClipShape(clipId, new ClipShape.Trapezoid(t.riseTime(), n.doubleValue()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Trapezoid t) flat.setValueQuiet(t.flatTime()); });
        box.getChildren().add(row("Flat time", flat));
    }

    private void addGaussian(VBox box, double duration, ClipShape.Gaussian initial) {
        var sigma = nf().range(0.1, duration * 2).step(1).decimals(1).unit("μs");
        sigma.setValue(initial.sigma());
        sigma.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            session.setClipShape(clipId, new ClipShape.Gaussian(n.doubleValue()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Gaussian g) sigma.setValueQuiet(g.sigma()); });
        box.getChildren().add(row("Sigma", sigma));
    }

    private void addTriangle(VBox box, ClipShape.Triangle initial) {
        var peak = nf().range(0, 1).step(0.05).decimals(3);
        peak.setValue(initial.peakPosition());
        peak.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            session.setClipShape(clipId, new ClipShape.Triangle(n.doubleValue()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Triangle t) peak.setValueQuiet(t.peakPosition()); });
        box.getChildren().add(row("Peak position", peak));
    }

    private void addSine(VBox box, ClipShape.Sine initial) {
        // Mode toggle — cycles vs. frequency
        var mode = new SegmentedControl<SineMode>()
            .options(List.of(SineMode.FREQUENCY, SineMode.CYCLES),
                m -> m == SineMode.FREQUENCY ? "Frequency" : "Cycles");
        mode.setValue(initial.cycles() > 0 ? SineMode.CYCLES : SineMode.FREQUENCY);
        box.getChildren().add(row("Mode", mode));

        var freq = nf().range(0.1, 10_000_000).step(100).scientific().unit("Hz");
        freq.setValue(initial.frequencyHz());
        freq.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sine s)) return;
            session.setClipShape(clipId, new ClipShape.Sine(n.doubleValue(), s.phase(), s.cycles()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sine s) freq.setValueQuiet(s.frequencyHz()); });
        box.getChildren().add(row("Frequency", freq));

        var cycles = nf().range(0, 10_000).step(0.25).decimals(3);
        cycles.setValue(initial.cycles());
        cycles.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sine s)) return;
            session.setClipShape(clipId, new ClipShape.Sine(s.frequencyHz(), s.phase(), n.doubleValue()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sine s) cycles.setValueQuiet(s.cycles()); });
        box.getChildren().add(row("Cycles", cycles));

        var phase = nf().range(-2 * Math.PI, 2 * Math.PI).step(0.1).decimals(3).unit("rad");
        phase.setValue(initial.phase());
        phase.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sine s)) return;
            session.setClipShape(clipId, new ClipShape.Sine(s.frequencyHz(), n.doubleValue(), s.cycles()));
        });
        pullers.add(c -> { if (c.shape() instanceof ClipShape.Sine s) phase.setValueQuiet(s.phase()); });
        box.getChildren().add(row("Phase", phase));

        // Mode toggle: switch between 0-cycles-driven (frequency) or positive cycles.
        mode.valueProperty().addListener((obs, o, n) -> {
            var clip = session.findClip(clipId);
            if (clip == null || !(clip.shape() instanceof ClipShape.Sine s)) return;
            if (n == SineMode.FREQUENCY && s.cycles() > 0) {
                session.setClipShape(clipId, new ClipShape.Sine(s.frequencyHz(), s.phase(), 0));
            } else if (n == SineMode.CYCLES && s.cycles() <= 0) {
                session.setClipShape(clipId, new ClipShape.Sine(s.frequencyHz(), s.phase(), 1));
            }
        });
    }

    private enum SineMode { FREQUENCY, CYCLES }

    // ── Points-of-interest rotation table (RF clips only) ──────────────────

    /**
     * For a clip on an RF (QUADRATURE-backed) track, show the net rotation the
     * clip applies to each currently-visible point-of-interest's magnetisation
     * in the rotating frame. Uses the POIs' last-simulated trajectories — the
     * values refresh whenever the trajectories update.
     */
    private Node buildPoiRotationSection() {
        var box = new VBox(4);
        if (poiEntries == null) {
            box.setVisible(false);
            box.setManaged(false);
            return box;
        }

        var title = new Label("Rotations at points of interest");
        title.getStyleClass().add("clip-inspector-section");
        var body = new VBox(4);
        box.getChildren().addAll(title, body);

        poiRefresher = () -> refreshPoiRotations(box, title, body);
        return box;
    }

    private void refreshPoiRotations(VBox container, Label title, VBox body) {
        var clip = session.findClip(clipId);
        boolean onRf = clip != null && isRfTrack(clip.trackId());

        if (!onRf || poiEntries == null) {
            container.setVisible(false);
            container.setManaged(false);
            body.getChildren().clear();
            return;
        }

        container.setVisible(true);
        container.setManaged(true);
        body.getChildren().clear();

        var visiblePois = poiEntries.stream().filter(IsochromatEntry::visible).toList();
        if (visiblePois.isEmpty()) {
            var msg = new Label("No visible points — add one in the Points pane to see rotations.");
            msg.getStyleClass().add("clip-inspector-hint");
            msg.setWrapText(true);
            body.getChildren().add(msg);
            return;
        }

        boolean anyTrajectory = false;
        for (var poi : visiblePois) {
            if (poi.trajectory() != null) { anyTrajectory = true; break; }
        }
        if (!anyTrajectory) {
            var msg = new Label("Run simulation to see the rotation applied at each point.");
            msg.getStyleClass().add("clip-inspector-hint");
            msg.setWrapText(true);
            body.getChildren().add(msg);
            return;
        }

        for (var poi : visiblePois) {
            body.getChildren().add(buildPoiRotationRow(poi, clip.startTime(), clip.endTime()));
        }
    }

    private Node buildPoiRotationRow(IsochromatEntry poi, double clipStart, double clipEnd) {
        var row = new VBox(2);
        row.getStyleClass().add("clip-inspector-poi-row");

        var colourDot = new Label("\u25CF");
        colourDot.setStyle("-fx-text-fill: " + toHex(poi.colour()) + ";");
        var nameLabel = new Label(poi.name() == null || poi.name().isBlank()
            ? String.format("(r=%.1f, z=%.1f) mm", poi.r(), poi.z())
            : poi.name());
        nameLabel.getStyleClass().add("clip-inspector-poi-name");
        var coords = new Label(String.format("r = %.1f, z = %.1f mm", poi.r(), poi.z()));
        coords.getStyleClass().add("clip-inspector-hint");
        var header = new HBox(6, colourDot, nameLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(header, coords);

        var analysis = ClipRotationAnalysis.ofClip(poi.trajectory(), clipStart, clipEnd);
        if (analysis == null) {
            var msg = new Label("  (no trajectory yet)");
            msg.getStyleClass().add("clip-inspector-hint");
            row.getChildren().add(msg);
            return row;
        }

        var angleLabel = new Label(String.format("  Flip %.1f\u00B0 about %s",
            analysis.angleDegrees(),
            formatAxis(analysis.axisX(), analysis.axisY(), analysis.axisZ())));
        angleLabel.getStyleClass().add("clip-inspector-poi-angle");

        var beforeAfter = new Label(String.format(
            "  M: (%s) \u2192 (%s)",
            formatMag(analysis.before().mx(), analysis.before().my(), analysis.before().mz()),
            formatMag(analysis.after().mx(), analysis.after().my(), analysis.after().mz())));
        beforeAfter.getStyleClass().add("clip-inspector-hint");

        row.getChildren().addAll(angleLabel, beforeAfter);
        return row;
    }

    /**
     * True if the track's target source is referenced as an I or Q envelope
     * by any {@link ax.xz.mri.model.circuit.CircuitComponent.Modulator} in
     * the active circuit — i.e. the track ultimately feeds an RF drive.
     */
    private boolean isRfTrack(String trackId) {
        var track = session.findTrack(trackId);
        if (track == null) return false;
        var src = session.sourceForChannel(track.outputChannel());
        if (src == null) return false;
        var circuit = session.activeCircuit();
        if (circuit == null) return false;
        for (var comp : circuit.components()) {
            if (!(comp instanceof ax.xz.mri.model.circuit.CircuitComponent.Modulator m)) continue;
            var i = ax.xz.mri.model.circuit.CircuitComponent.Modulator.inputSource(m, "in0", circuit);
            var q = ax.xz.mri.model.circuit.CircuitComponent.Modulator.inputSource(m, "in1", circuit);
            if (i != null && src.name().equals(i.name())) return true;
            if (q != null && src.name().equals(q.name())) return true;
        }
        return false;
    }

    private static String formatMag(double mx, double my, double mz) {
        return String.format("%+.2f, %+.2f, %+.2f", mx, my, mz);
    }

    /**
     * Compact axis display. Pure basis directions (±x̂, ±ŷ, ±ẑ) render as a
     * single glyph; arbitrary directions render as a (x, y, z) triple.
     */
    private static String formatAxis(double nx, double ny, double nz) {
        double tol = 0.02;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax > 1 - tol && ay < tol && az < tol) return (nx > 0 ? "+x\u0302" : "\u2212x\u0302");
        if (ay > 1 - tol && ax < tol && az < tol) return (ny > 0 ? "+y\u0302" : "\u2212y\u0302");
        if (az > 1 - tol && ax < tol && ay < tol) return (nz > 0 ? "+z\u0302" : "\u2212z\u0302");
        return String.format("(%+.2f, %+.2f, %+.2f)", nx, ny, nz);
    }

    private static String toHex(javafx.scene.paint.Color c) {
        if (c == null) return "#888888";
        return String.format("#%02X%02X%02X",
            (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue() * 255));
    }

    // ── Action row ──────────────────────────────────────────────────────────

    private Node buildActionRow() {
        var del = new Button("Delete");
        del.setOnAction(e -> session.deleteSelectedClips());
        var dup = new Button("Duplicate");
        dup.setOnAction(e -> session.duplicateSelectedClips());
        var row = new HBox(6, del, dup);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════════════════

    private NumberField nf() {
        return new NumberField().prefColumnCount(7);
    }

    private Node row(String label, Node field) {
        var row = new HBox(6, labelCol(label), field);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private Label labelCol(String text) {
        var l = new Label(text);
        l.getStyleClass().add("clip-inspector-label");
        l.setMinWidth(90);
        l.setPrefWidth(90);
        return l;
    }

    @SuppressWarnings("unused")
    private String channelLabel(SequenceChannel channel) {
        if (channel == null) return "\u2014";
        var src = session.sourceForChannel(channel);
        if (src == null) return channel.sourceName() + "[" + channel.subIndex() + "]";
        return ax.xz.mri.model.sequence.ClipBaker.defaultTrackName(src, channel.subIndex());
    }

    private String formatPeak(SignalClip clip, EigenfieldDocument ef) {
        if (ef == null) return "";
        double physical = clip.amplitude() * ef.defaultMagnitude();
        return "\u2248 " + formatSI(physical, ef.units());
    }

    private static String formatSI(double value, String units) {
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

    /** Spacer for horizontal flex. */
    @SuppressWarnings("unused")
    private static Region spacer() {
        var r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
