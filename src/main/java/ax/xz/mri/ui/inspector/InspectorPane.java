package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.PropertySheet;

/**
 * Right-sidebar inspector — context-sensitive.
 *
 * <p>When a sequence editor is active, the body shows a STATIC top region
 * (config selector + sequence header) that survives clip-selection churn,
 * plus a DYNAMIC body that swaps between clip details and a "no clip
 * selected" hint. PropertySheet renders flat (no collapsible categories);
 * cut/copy/paste/delete are wired through {@code SelectionContext} on the
 * sequence editor pane, not through inspector buttons.
 *
 * <p>When no sequence is active, the body shows the metadata of the
 * explorer's currently-inspected project node.
 */
public final class InspectorPane extends WorkbenchPane {
    private final VBox content = new VBox(8);
    private final VBox dynamicBody = new VBox(8);
    private final PaneContext paneContext;

    private EditSession wiredSession;
    private String wiredClipId;

    public InspectorPane(PaneContext paneContext) {
        super(paneContext);
        this.paneContext = paneContext;
        setPaneTitle("Inspector");
        content.setPadding(new Insets(10));

        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setPaneContent(scroll);

        paneContext.session().project.inspector.inspectedNodeId.addListener((obs, o, n) -> rebuildTop());
        paneContext.session().project.explorer.structureRevision.addListener((obs, o, n) -> rebuildTop());
        paneContext.session().activeEditSession.addListener((obs, o, n) -> rebuildTop());
        rebuildTop();
    }

    /**
     * Replace the entire content. Called only on session-level changes
     * (active session swap, project structure changes, explorer-selection
     * changes) — NOT on clip selection within a session, which only swaps
     * {@link #dynamicBody}.
     */
    private void rebuildTop() {
        content.getChildren().clear();
        var es = paneContext.session().activeEditSession.get();
        if (es != null) {
            content.getChildren().addAll(
                SequenceConfigSelector.build(paneContext.session(), es),
                new Separator(),
                sequenceHeader(es),
                new Separator(),
                dynamicBody
            );
            VBox.setVgrow(dynamicBody, Priority.ALWAYS);
            rewireSelectionListener(es);
            wiredSession = es;
            populateDynamicBody(es);
            return;
        }

        wiredSession = null;
        wiredClipId = null;
        var repo = paneContext.session().state.current();
        var nodeId = paneContext.session().project.inspector.inspectedNodeId.get();
        var node = nodeId == null ? null : repo.node(nodeId);
        if (node == null) {
            var hint = new Label("Select an item in the explorer to inspect it.");
            hint.getStyleClass().add("inspector-hint");
            content.getChildren().add(hint);
            return;
        }
        populateForNode(node);
    }

    /**
     * Build clip-detail vs. "no clip selected" body. Called on rebuildTop AND
     * on primary-clip-id change.
     */
    private void populateDynamicBody(EditSession es) {
        dynamicBody.getChildren().clear();
        var primary = es.primarySelectedClip();
        if (primary == null) {
            wiredClipId = null;
            var hint = new Label("No clip selected.");
            hint.getStyleClass().add("inspector-hint");
            dynamicBody.getChildren().add(hint);
            return;
        }
        wiredClipId = primary.id();
        dynamicBody.getChildren().addAll(
            clipHeader(es, primary),
            new Separator(),
            buildPropertySheet(es, primary),
            new Separator(),
            shapeParamsSection(es, primary),
            new Separator(),
            FlipAngleSection.build(paneContext.session(), primary)
        );
    }

    private final ChangeListener<String> selectionListener = (obs, oldId, newId) -> {
        if (wiredSession == null) return;
        if (java.util.Objects.equals(newId, wiredClipId)) return;
        populateDynamicBody(wiredSession);
    };

    private void rewireSelectionListener(EditSession es) {
        if (wiredSession != null && wiredSession != es) {
            wiredSession.selection.primary().removeListener(selectionListener);
        }
        if (es != null && wiredSession != es) {
            es.selection.primary().addListener(selectionListener);
        }
    }

    /**
     * Sequence-level metadata: name, dt, total duration, track/clip counts.
     * The active config NAME is shown in the SequenceConfigSelector combo
     * directly, not duplicated here.
     */
    private Node sequenceHeader(EditSession es) {
        var title = new Label();
        title.getStyleClass().add("inspector-section-title");
        var detail = new Label();
        detail.getStyleClass().add("inspector-hint");
        detail.setWrapText(true);

        var lastTitle  = new String[]{null};
        var lastDetail = new String[]{null};
        Runnable refresh = () -> {
            var doc = es.originalDocument.get();
            String name = doc != null ? doc.name() : "(untitled)";
            int trackCount = es.tracks.size();
            int clipCount  = es.clips.size();
            String t = "Sequence: " + name;
            String d = String.format("dt %.2f μs · %.0f μs total · %d track%s, %d clip%s",
                es.dt.get(), es.totalDuration.get(),
                trackCount, trackCount == 1 ? "" : "s",
                clipCount,  clipCount  == 1 ? "" : "s");
            if (!java.util.Objects.equals(t, lastTitle[0])) { lastTitle[0] = t; title.setText(t); }
            if (!java.util.Objects.equals(d, lastDetail[0])) { lastDetail[0] = d; detail.setText(d); }
        };
        refresh.run();
        es.revision.addListener((obs, o, n) -> refresh.run());

        return new VBox(2, title, detail);
    }

    private Node clipHeader(EditSession es, SignalClip clip) {
        var trackLabel = new Label();
        trackLabel.getStyleClass().add("inspector-track-name");
        var arrow = new Label("→");
        arrow.getStyleClass().add("inspector-arrow");
        var shapeCombo = new ComboBox<ClipKind>();
        shapeCombo.getItems().setAll(ClipKind.values());
        shapeCombo.valueProperty().addListener((obs, o, n) -> {
            var current = es.findClip(clip.id());
            if (current != null && n != null && n != current.shape().kind()) {
                es.changeClipKind(clip.id(), n);
            }
        });

        var lastTrackId = new String[]{null};
        var lastKind    = new ClipKind[]{null};
        Runnable refresh = () -> {
            var c = es.findClip(clip.id());
            if (c == null) return;
            String trackId = c.trackId();
            if (!java.util.Objects.equals(trackId, lastTrackId[0])) {
                lastTrackId[0] = trackId;
                var t = es.findTrack(trackId);
                trackLabel.setText(t != null ? t.name() : "—");
            }
            ClipKind kind = c.shape().kind();
            if (!java.util.Objects.equals(kind, lastKind[0])) {
                lastKind[0] = kind;
                shapeCombo.setValue(kind);
            }
        };
        refresh.run();
        es.revision.addListener((obs, o, n) -> refresh.run());

        var row = new HBox(8, trackLabel, arrow, shapeCombo);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node buildPropertySheet(EditSession es, SignalClip clip) {
        var items = FXCollections.<PropertySheet.Item>observableArrayList();
        items.setAll(ClipPropertyItems.build(es, clip.id()));
        var sheet = new PropertySheet(items);
        // Flat mode (no collapsible TitledPanes per category) — the user
        // explicitly asked for HFSS-style flat property lists.
        sheet.setMode(PropertySheet.Mode.NAME);
        sheet.setSearchBoxVisible(false);
        sheet.setModeSwitcherVisible(false);
        sheet.setPropertyEditorFactory(item -> ClipPropertyItems.editorFor(es, clip.id(), item));
        sheet.getStyleClass().add("clip-inspector-sheet");
        VBox.setVgrow(sheet, Priority.ALWAYS);
        return sheet;
    }

    private Node shapeParamsSection(EditSession es, SignalClip clip) {
        var box = new VBox(6);
        var title = new Label("Shape parameters");
        title.getStyleClass().add("inspector-section-title");
        box.getChildren().add(title);

        var lastShape = new ClipShape[]{null};
        Runnable refresh = () -> {
            var c = es.findClip(clip.id());
            if (c == null) return;
            if (java.util.Objects.equals(c.shape(), lastShape[0])) return;
            lastShape[0] = c.shape();
            box.getChildren().setAll(title);
            ShapeParamsBuilder.populate(box, es, c);
        };
        refresh.run();
        es.revision.addListener((obs, o, n) -> refresh.run());
        return box;
    }

    // ── Project-node metadata fallback ───────────────────────────────────────

    private void populateForNode(ProjectNode node) {
        var head = new Label(switch (node) {
            case SequenceDocument s -> "Sequence: " + s.name();
            case SimulationConfigDocument s -> "Simulation Config: " + s.name();
            case HardwareConfigDocument s -> "Hardware Config: " + s.name();
            case EigenfieldDocument s -> "Eigenfield: " + s.name();
            case SubstanceDocument s -> "Substance: " + s.name();
            case ax.xz.mri.project.ProcedureDocument s -> "Procedure: " + s.name();
            default -> node.kind().name();
        });
        head.getStyleClass().add("inspector-section-title");
        content.getChildren().addAll(head, new Separator(), new Label("Type: " + node.kind().name()));

        // Kind-specific quick facts. Kept terse — the tab editor is the
        // place for full edits; the inspector is the at-a-glance summary.
        if (node instanceof SubstanceDocument sub) {
            switch (sub.substance()) {
                case NvEnsemble nv -> {
                    var geom = nv.arrayGeometry();
                    content.getChildren().addAll(
                        new Label("Kind: NV ensemble"),
                        new Label("Centres: " + geom.n() + " (" + geom.shape() + ")"),
                        new Label("Depth: " + String.format("%.0f nm", geom.depthMetres() * 1e9)),
                        new Label("Shot seed: " + nv.shotSeed()),
                        new Label("Interaction threshold: "
                            + String.format("%.2f nm", nv.interactionThresholdMetres() * 1e9)));
                }
                case ContinuousMagnetisation cm -> content.getChildren().addAll(
                    new Label("Kind: continuous magnetisation"),
                    new Label("T₁: " + String.format("%.3f s", cm.t1Seconds())),
                    new Label("T₂: " + String.format("%.3f s", cm.t2Seconds())),
                    new Label("γ: " + String.format("%.3e rad/s/T", cm.gammaRadPerSecPerTesla())),
                    new Label("m_z0: " + String.format("%.3f", cm.mz0())));
            }
        } else if (node instanceof ax.xz.mri.project.ProcedureDocument proc) {
            content.getChildren().addAll(
                new Label("Source lines: " + countLines(proc.source())));
        } else if (node instanceof EigenfieldDocument ef) {
            content.getChildren().addAll(
                new Label("Symmetry: " + ef.symmetry()),
                new Label("Units: " + (ef.units().isBlank() ? "(dimensionless)" : ef.units())),
                new Label("Source lines: " + countLines(ef.script())));
        } else if (node instanceof SequenceDocument seq) {
            var clipSeq = seq.clipSequence();
            content.getChildren().addAll(
                new Label("Clips: " + clipSeq.clips().size()),
                new Label("Tracks: " + clipSeq.tracks().size()),
                new Label("Total duration: " + ax.xz.mri.util.SiFormat.time(clipSeq.totalDuration() * 1e6)));
        } else if (node instanceof SimulationConfigDocument sim) {
            var cfg = sim.config();
            content.getChildren().addAll(
                new Label("B₀: " + String.format("%.4f T", cfg.referenceB0Tesla())),
                new Label("dt: " + ax.xz.mri.util.SiFormat.time(cfg.dtSeconds() * 1e6)));
        }
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }
}
