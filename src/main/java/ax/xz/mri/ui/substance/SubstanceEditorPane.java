package ax.xz.mri.ui.substance;

import module ax.xz.mri;
import module javafx.controls;
import module javafx.graphics;

// Non-exported types still need explicit imports — module ax.xz.mri only
// surfaces the packages listed in module-info exports.
import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.DocumentEditor;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.EditorSection;
import ax.xz.mri.ui.workbench.framework.EditorSection.SectionCard;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Document editor for a {@link SubstanceDocument}.
 *
 * <p>For NV ensembles, the editor uses a {@link NvScatter3DCanvas}-driven
 * direct-manipulation viewport — drag NVs, plane / line constraints, an
 * optional eigenfield arrow overlay, status bar reporting the active tool
 * and cursor world position. The legacy 1-D spinner form (Shape / Count /
 * Length / Depth) is gone; the centre list is the source of truth and
 * stamp buttons (Linear, Grid, Random) populate it for convenience.
 *
 * <p>For continuous-magnetisation substances, the form stays a plain
 * tissue-physics card (T₁ / T₂ / γ / m_z0).
 */
public final class SubstanceEditorPane extends WorkbenchPane {

    private final DocumentEditor<SubstanceDocument> editor;
    private SubstanceDocument document;

    private final HBox headerStrip = new HBox();
    private final Label headerName = new Label();
    private final Label headerSubtitle = new Label();
    private final Label headerBadge = new Label();

    private final TextField nameField = new TextField();

    // Bloch fields.
    private TextField cmT1SecField;
    private TextField cmT2SecField;
    private TextField cmGammaField;
    private TextField cmMz0Field;

    // NV editor controls.
    private final NvScatter3DCanvas scatter = new NvScatter3DCanvas();
    private ToggleGroup toolGroup;
    private ComboBox<NvAxis> nvAxisCombo;
    private TextField nvSeedField;
    private TextField nvThresholdNmField;
    private ComboBox<ConstraintOption> constraintCombo;
    private ComboBox<EigenfieldOption> eigenfieldCombo;
    private Label nvCentreCountLabel;
    private final HBox statusBar = new HBox(6);

    private final VBox sectionStack = new VBox();

    private boolean suppressFormListeners;
    private Runnable onTitleChanged;
    /** Tracks the substance subtype currently rendered so we rebuild only when it changes. */
    private Class<?> currentKind;

    public SubstanceEditorPane(PaneContext paneContext, SubstanceDocument document) {
        super(paneContext);
        var stateMgr = paneContext.session().state;
        var scope = Scope.indexed(Scope.root(), "substances", document.id());
        if (stateMgr.current().substance(document.id()) == null) {
            stateMgr.dispatch(Mutation.structural(scope, null, document, "Create substance"));
        }
        this.editor = new DocumentEditor<>(stateMgr, scope, "substance-editor", SubstanceDocument.class);
        this.document = editor.value();
        this.currentKind = document.substance().getClass();
        editor.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            var newKind = n.substance().getClass();
            this.document = n;
            // Only rebuild the section stack when the substance KIND changes (Bloch ↔ NV).
            // For in-kind value edits the existing fields already show the user's input —
            // a full rebuild would clobber focus, scroll position, and produce a visible
            // layout jump.
            if (!suppressFormListeners && !newKind.equals(currentKind)) {
                currentKind = newKind;
                hydrateFromDocument();
            } else if (!suppressFormListeners && n.substance() instanceof NvEnsemble nv) {
                // In-kind NV edit — refresh the canvas if the centres list changed
                // out from under us (e.g. project-level undo/redo).
                syncCanvasFromDocument(nv);
            }
            paintHeader();
            setPaneTitle("Substance: " + n.name());
            notifyTitleChanged();
        });

        setPaneTitle("Substance: " + document.name());
        sectionStack.getStyleClass().add("editor-section-stack");

        var scroll = new ScrollPane(sectionStack);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinWidth(320);
        scroll.setPrefWidth(360);
        scroll.setMaxWidth(420);

        var viewportHolder = buildViewportArea();

        var split = new SplitPane(scroll, viewportHolder);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.32);
        SplitPane.setResizableWithParent(scroll, false);

        var root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(split);
        root.setBottom(buildStatusBar());
        setPaneContent(root);

        hookCanvasMutations();
        hydrateFromDocument();
        paintHeader();
    }

    /* ── Header ─────────────────────────────────────────────────────────── */

    private Node buildHeader() {
        headerStrip.getStyleClass().add("editor-page-header");
        headerStrip.setAlignment(Pos.CENTER_LEFT);
        headerBadge.getStyleClass().add("editor-kind-badge");
        headerName.getStyleClass().add("editor-page-name");
        headerSubtitle.getStyleClass().add("editor-page-subtitle");
        var sep = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        sep.getStyleClass().add("editor-page-separator");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        headerStrip.getChildren().setAll(headerBadge, headerName, sep, headerSubtitle, spacer);
        return headerStrip;
    }

    private void paintHeader() {
        var s = document.substance();
        headerName.setText(document.name());
        switch (s) {
            case ContinuousMagnetisation cm -> {
                headerBadge.setText("BLOCH");
                headerSubtitle.setText(String.format(
                    "T₁ %.2f s · T₂ %.2f s · γ %.3e",
                    cm.t1Seconds(), cm.t2Seconds(), cm.gammaRadPerSecPerTesla()));
            }
            case NvEnsemble nv -> {
                headerBadge.setText("NV");
                headerSubtitle.setText(String.format(
                    "%d centres · cluster cut-off %.0f nm",
                    nv.centres().size(), nv.interactionThresholdMetres() * 1e9));
            }
        }
    }

    /* ── Form ───────────────────────────────────────────────────────────── */

    private void hydrateFromDocument() {
        suppressFormListeners = true;
        try {
            var s = document.substance();
            sectionStack.getChildren().setAll(buildSections(s));
            boolean isNv = s instanceof NvEnsemble;
            // The 3-D viewport is NV-specific. For Bloch substances it would
            // render an empty box with axes — distracting and pointless. Hide
            // it (and the bottom status bar) so the editor reads as a focused
            // physics form.
            viewportHolder.setVisible(isNv);
            viewportHolder.setManaged(isNv);
            statusBar.setVisible(isNv);
            statusBar.setManaged(isNv);
            if (s instanceof NvEnsemble nv) {
                syncCanvasFromDocument(nv);
            }
        } finally {
            suppressFormListeners = false;
        }
    }

    private List<Node> buildSections(Substance s) {
        return switch (s) {
            case ContinuousMagnetisation cm -> List.of(
                buildIdentificationCard().node(),
                buildContinuousPhysicsCard(cm).node(),
                buildPortsCard(s).node()
            );
            case NvEnsemble nv -> List.of(
                buildIdentificationCard().node(),
                buildNvToolPaletteCard().node(),
                buildNvConstraintCard(nv).node(),
                buildNvStampsCard().node(),
                buildNvShotsCard(nv).node(),
                buildNvOverlayCard().node(),
                buildPortsCard(s).node()
            );
        };
    }

    private SectionCard buildIdentificationCard() {
        nameField.setText(document.name());
        nameField.setPromptText("Name");
        nameField.setPrefColumnCount(28);
        nameField.getStyleClass().add("editor-field");
        nameField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) applyName(nameField.getText());
        });
        nameField.setOnAction(e -> applyName(nameField.getText()));

        var card = EditorSection.section("Identification");
        card.addRow("Name", nameField);
        return card;
    }

    private SectionCard buildContinuousPhysicsCard(ContinuousMagnetisation cm) {
        cmT1SecField    = EditorSection.doubleField(cm.t1Seconds(),               v -> applyContinuousEdit());
        cmT2SecField    = EditorSection.doubleField(cm.t2Seconds(),               v -> applyContinuousEdit());
        cmGammaField    = EditorSection.doubleField(cm.gammaRadPerSecPerTesla(),  v -> applyContinuousEdit());
        cmMz0Field      = EditorSection.doubleField(cm.mz0(),                     v -> applyContinuousEdit());

        var card = EditorSection.section("Physics");
        card.addRow("T₁",   cmT1SecField, "s");
        card.addRow("T₂",   cmT2SecField, "s");
        card.addRow("γ",    cmGammaField, "rad·s⁻¹·T⁻¹");
        card.addRow("m_z0", cmMz0Field);
        return card;
    }

    /* ── NV editor cards ──────────────────────────────────────────────── */

    private SectionCard buildNvToolPaletteCard() {
        toolGroup = new ToggleGroup();
        var card = EditorSection.section("Tool");
        var box = new HBox(4);
        for (var tool : NvEditorTool.values()) {
            var btn = new ToggleButton(tool.displayName());
            btn.setUserData(tool);
            btn.setToggleGroup(toolGroup);
            btn.setOnAction(e -> {
                scatter.activeToolProperty().set(tool);
                refreshStatusBar();
            });
            if (tool == scatter.activeToolProperty().get()) btn.setSelected(true);
            box.getChildren().add(btn);
        }
        toolGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && n.getUserData() instanceof NvEditorTool t) {
                scatter.activeToolProperty().set(t);
                refreshStatusBar();
            }
        });
        card.addNode(box);

        var resetView = new HBox(4);
        for (var view : new String[] {"ISO", "Top", "Side"}) {
            var btn = new Button(view);
            btn.setOnAction(e -> {
                switch (view) {
                    case "ISO"  -> scatter.setPreset(0.6, 0.3);
                    case "Top"  -> scatter.setPreset(0.0, 1.4);
                    case "Side" -> scatter.setPreset(0.0, 0.0);
                }
            });
            resetView.getChildren().add(btn);
        }
        var reset = new Button("Reset");
        reset.setOnAction(e -> scatter.resetView());
        resetView.getChildren().add(reset);
        card.addNode(resetView);
        return card;
    }

    private SectionCard buildNvConstraintCard(NvEnsemble nv) {
        constraintCombo = new ComboBox<>();
        constraintCombo.getItems().setAll(defaultConstraintOptions());
        constraintCombo.getSelectionModel().select(0);
        constraintCombo.setOnAction(e -> {
            var opt = constraintCombo.getValue();
            if (opt != null) {
                scatter.constraintProperty().set(opt.constraint());
                refreshStatusBar();
            }
        });

        var axisOptions = new NvAxis[] {
            NvAxis.AXIS_PLUS_Z, NvAxis.AXIS_111, NvAxis.AXIS_111_BAR,
            NvAxis.AXIS_1_BAR_11, NvAxis.AXIS_11_BAR_1
        };
        var geom = nv.arrayGeometry();
        var defaultAxis = geom.axis() == null ? NvAxis.AXIS_PLUS_Z : geom.axis();
        nvAxisCombo = EditorSection.enumField(defaultAxis, v -> applyNvAxisEdit(), axisOptions);

        var card = EditorSection.section("Constraint");
        card.addRow("Surface", constraintCombo);
        card.addRow("NV axis", nvAxisCombo);
        nvCentreCountLabel = new Label(nv.centres().size() + " centres");
        nvCentreCountLabel.setStyle("-fx-text-fill: -studio-text-tertiary; -fx-font-size: 11;");
        card.addNode(nvCentreCountLabel);
        return card;
    }

    /** Built-in constraint presets. Matches what the plan calls for. */
    private static List<ConstraintOption> defaultConstraintOptions() {
        var list = new ArrayList<ConstraintOption>();
        list.add(new ConstraintOption("None (free 3-D)", new NvConstraint.None()));
        list.add(new ConstraintOption("Plane Z = −50 nm", new NvConstraint.PlaneZ(-50e-9)));
        list.add(new ConstraintOption("Plane Z = 0", new NvConstraint.PlaneZ(0)));
        list.add(new ConstraintOption("Plane Y = 0", new NvConstraint.PlaneY(0)));
        list.add(new ConstraintOption("Plane X = 0", new NvConstraint.PlaneX(0)));
        list.add(new ConstraintOption("Line X (y=0, z=−50 nm)", new NvConstraint.LineX(0, -50e-9)));
        list.add(new ConstraintOption("Line Y (x=0, z=−50 nm)", new NvConstraint.LineY(0, -50e-9)));
        list.add(new ConstraintOption("Line Z (x=0, y=0)", new NvConstraint.LineZ(0, 0)));
        return list;
    }

    private record ConstraintOption(String label, NvConstraint constraint) {
        @Override public String toString() { return label; }
    }

    private SectionCard buildNvStampsCard() {
        var linear = new Button("Linear ×16");
        linear.setOnAction(e -> stampLinear(16, 1e-6, -50e-9));
        var grid = new Button("Grid 4×4");
        grid.setOnAction(e -> stampGrid(4, 4, 1e-6, -50e-9));
        var random = new Button("Random ×16");
        random.setOnAction(e -> stampRandom(16, 1e-6, -50e-9, currentNvSeed()));
        var clear = new Button("Clear");
        clear.setOnAction(e -> commitCentres(List.of()));

        var card = EditorSection.section("Stamps");
        var row = new HBox(4, linear, grid, random, clear);
        card.addNode(row);
        var hint = new Label("Stamps replace the centre list. Drag in the viewport to fine-tune.");
        hint.setStyle("-fx-text-fill: -studio-text-tertiary; -fx-font-size: 10;");
        hint.setWrapText(true);
        card.addNode(hint);
        return card;
    }

    private SectionCard buildNvShotsCard(NvEnsemble nv) {
        nvSeedField        = EditorSection.longField(nv.shotSeed(),                             v -> applyNvShotsEdit());
        nvThresholdNmField = EditorSection.doubleField(nv.interactionThresholdMetres() * 1e9,   v -> applyNvShotsEdit());

        var card = EditorSection.section("Shots");
        card.addRow("Seed",            nvSeedField);
        card.addRow("Cluster cut-off", nvThresholdNmField, "nm");
        return card;
    }

    private SectionCard buildNvOverlayCard() {
        eigenfieldCombo = new ComboBox<>();
        eigenfieldCombo.getItems().add(new EigenfieldOption(null, "(none)"));
        var state = paneContext.session().project.project();
        if (state != null) {
            for (var id : state.eigenfieldIds()) {
                var doc = state.eigenfield(id);
                if (doc != null) eigenfieldCombo.getItems().add(new EigenfieldOption(doc, doc.name()));
            }
        }
        eigenfieldCombo.getSelectionModel().select(0);
        eigenfieldCombo.setOnAction(e -> applyOverlayEigenfield());
        // Refresh the list when project state mutates — new eigenfields get
        // surfaced without re-opening the pane.
        paneContext.session().project.state().currentProperty()
            .addListener((obs, o, n) -> refreshEigenfieldList());

        var card = EditorSection.section("Eigenfield overlay");
        card.addRow("Field", eigenfieldCombo);
        var hint = new Label("Optional: render an eigenfield's arrows behind the NV scatter.");
        hint.setStyle("-fx-text-fill: -studio-text-tertiary; -fx-font-size: 10;");
        hint.setWrapText(true);
        card.addNode(hint);
        return card;
    }

    private SectionCard buildPortsCard(Substance s) {
        var card = EditorSection.section("Ports");
        var channels = s.outputChannels();

        // Substance-block CONTROL inputs (drawn from sequence tracks).
        if (s instanceof NvEnsemble) {
            card.addNode(portRow("laser_on", "CONTROL", "in", "#3c8a52"));
        }
        // Output channels — fixed display order so the pane reads the same every time.
        if (channels.contains(MagneticMoment.class)) {
            card.addNode(portRow("magnetic moment", "MAGNETIC", "ambient", "#a04a8c"));
        }
        if (channels.contains(PhotonClickRate.class)) {
            card.addNode(portRow("clicks_red", "OPTICAL", "out", "#b3531a"));
        }
        return card;
    }

    private static HBox portRow(String name, String kind, String direction, String accentHex) {
        var pip = new Label(kind);
        pip.getStyleClass().add("port-chip");
        pip.setStyle("-fx-background-color: " + accentHex + "; -fx-text-fill: white; "
            + "-fx-padding: 1 6 1 6; -fx-background-radius: 2; "
            + "-fx-font-size: 9.5; -fx-font-weight: 700;");

        var nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -studio-text;");

        var dir = new Label(direction);
        dir.setStyle("-fx-font-size: 10; -fx-text-fill: -studio-text-tertiary;");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var row = new HBox(8, pip, nameLabel, spacer, dir);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));
        return row;
    }

    /* ── Viewport + status ─────────────────────────────────────────────── */

    private final StackPane viewportHolder = new StackPane();

    private Node buildViewportArea() {
        viewportHolder.getChildren().setAll(scatter);
        viewportHolder.setPadding(new Insets(0));
        VBox.setVgrow(viewportHolder, Priority.ALWAYS);
        return viewportHolder;
    }

    private Node buildStatusBar() {
        // Inline style with concrete colours — the CSS-variable lookup
        // inline-style path doesn't resolve `-studio-*` tokens reliably and
        // produces ClassCastException warnings at load time.
        statusBar.setStyle("-fx-padding: 4 10 4 10; -fx-background-color: #e8ebee; "
            + "-fx-font-size: 10.5;");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setMaxWidth(Double.MAX_VALUE);
        // Pin the height so cursor moves never reflow the parent VBox.
        statusBar.setMinHeight(22);
        statusBar.setPrefHeight(22);
        statusBar.setMaxHeight(22);
        scatter.hoverWorldPositionProperty().addListener((obs, o, n) -> refreshStatusBar());
        scatter.centres().addListener((javafx.collections.ListChangeListener<NvCentre>) c -> {
            if (nvCentreCountLabel != null) {
                nvCentreCountLabel.setText(scatter.centres().size() + " centres");
            }
            refreshStatusBar();
        });
        refreshStatusBar();
        return statusBar;
    }

    private void refreshStatusBar() {
        statusBar.getChildren().clear();
        if (!(document.substance() instanceof NvEnsemble nv)) return;
        var pos = scatter.hoverWorldPositionProperty().get();
        var tool = scatter.activeToolProperty().get();
        var segments = java.util.List.of(
            tool.displayName(),
            tool.hint(),
            String.format("cursor x=%.2f µm  y=%.2f µm  z=%.0f nm",
                pos.x() * 1e6, pos.y() * 1e6, pos.z() * 1e9),
            nv.centres().size() + " centres"
        );
        boolean first = true;
        for (var seg : segments) {
            if (!first) statusBar.getChildren().add(new javafx.scene.control.Separator(
                javafx.geometry.Orientation.VERTICAL));
            var label = new Label(seg);
            label.setStyle("-fx-text-fill: #4a525c;");
            statusBar.getChildren().add(label);
            first = false;
        }
    }

    /* ── Canvas ↔ document wiring ──────────────────────────────────────── */

    private void hookCanvasMutations() {
        scatter.setOnCentresMutated(this::commitCentres);
        scatter.setContextMenuRequest((hit, world, sx, sy) -> {
            var menu = new ContextMenu();
            if (hit >= 0) {
                var del = new MenuItem("Delete NV");
                del.setOnAction(e -> {
                    if (hit < scatter.centres().size()) {
                        scatter.centres().remove(hit);
                        commitCentres(scatter.centres());
                    }
                });
                var dup = new MenuItem("Duplicate NV");
                dup.setOnAction(e -> {
                    if (hit < scatter.centres().size()) {
                        var c = scatter.centres().get(hit);
                        var shifted = new NvCentre(
                            c.xMetres() + 50e-9, c.yMetres(), c.zMetres(), c.axis());
                        var next = new ArrayList<>(scatter.centres());
                        next.add(shifted);
                        commitCentres(next);
                    }
                });
                menu.getItems().addAll(del, dup);
            } else {
                var add = new MenuItem(String.format(
                    "Add NV here (%.2f µm, %.2f µm, %.0f nm)",
                    world.x() * 1e6, world.y() * 1e6, world.z() * 1e9));
                add.setOnAction(e -> {
                    var projected = scatter.constraintProperty().get().project(world);
                    var next = new ArrayList<>(scatter.centres());
                    next.add(new NvCentre(projected.x(), projected.y(), projected.z(), NvAxis.AXIS_PLUS_Z));
                    commitCentres(next);
                });
                menu.getItems().add(add);
                menu.getItems().add(new SeparatorMenuItem());
                var reset = new MenuItem("Reset view");
                reset.setOnAction(e -> scatter.resetView());
                menu.getItems().add(reset);
            }
            menu.show(scatter, sx, sy);
        });
    }

    /** Replace the document's centre list with the supplied centres. */
    private void commitCentres(List<NvCentre> next) {
        if (!(document.substance() instanceof NvEnsemble nv)) return;
        if (next.isEmpty()) {
            // CUSTOM geometry requires a non-empty centres list; treat empty
            // as "use a single placeholder at the origin so the document
            // stays valid". Stamps re-fill immediately.
            next = List.of(new NvCentre(0, 0, -50e-9, currentAxis()));
        }
        var nextGeom = new NvArrayGeometry(
            NvArrayShape.CUSTOM,
            next.size(),
            // CUSTOM uses customCentres; length/depth are required by the
            // record but unused for layout. Keep them ≥ 0 for the validator.
            Math.max(1e-9, nv.arrayGeometry().lengthMetres()),
            Math.max(0, nv.arrayGeometry().depthMetres()),
            currentAxis(),
            nv.arrayGeometry().seed(),
            List.copyOf(next));
        var nextSubstance = new NvEnsemble(
            nextGeom, nv.physics(), nv.shotSeed(), nv.interactionThresholdMetres());
        if (Objects.equals(nextSubstance, nv)) return;
        editor.apply(d -> d.withSubstance(nextSubstance), "Edit NV centres");
    }

    /** Push the document's centre list into the canvas (called after document mutations). */
    private void syncCanvasFromDocument(NvEnsemble nv) {
        if (scatter.centres().equals(nv.centres())) return;
        scatter.centres().setAll(nv.centres());
        if (nvCentreCountLabel != null) {
            nvCentreCountLabel.setText(nv.centres().size() + " centres");
        }
    }

    private NvAxis currentAxis() {
        if (nvAxisCombo != null && nvAxisCombo.getValue() != null) return nvAxisCombo.getValue();
        return NvAxis.AXIS_PLUS_Z;
    }

    private long currentNvSeed() {
        if (document.substance() instanceof NvEnsemble nv) return nv.arrayGeometry().seed();
        return 0L;
    }

    /* ── Edit appliers ────────────────────────────────────────────────── */

    private void applyName(String raw) {
        var name = raw == null ? "" : raw.strip();
        if (name.isBlank() || name.equals(document.name())) return;
        editor.apply(d -> d.withName(name), "Rename substance");
        paneContext.session().project.explorer.refresh();
    }

    private void applyContinuousEdit() {
        if (suppressFormListeners) return;
        if (!(document.substance() instanceof ContinuousMagnetisation cm)) return;
        try {
            double t1    = parseDouble(cmT1SecField.getText(), cm.t1Seconds());
            double t2    = parseDouble(cmT2SecField.getText(), cm.t2Seconds());
            double gamma = parseDouble(cmGammaField.getText(), cm.gammaRadPerSecPerTesla());
            double mz0   = parseDouble(cmMz0Field.getText(), cm.mz0());
            var next = cm.withT1Seconds(t1).withT2Seconds(t2).withGamma(gamma).withMz0(mz0);
            if (Objects.equals(next, cm)) return;
            editor.apply(d -> d.withSubstance(next), "Edit Bloch");
            setStatus("", false);
        } catch (RuntimeException ex) {
            setStatus(ex.getMessage(), true);
        }
    }

    private void applyNvAxisEdit() {
        if (suppressFormListeners) return;
        if (!(document.substance() instanceof NvEnsemble nv)) return;
        var axis = currentAxis();
        // Apply the axis to every existing centre — uniform NV-array axis is
        // the v1 invariant. Per-centre axes wait for a future Pieces enhancement.
        var next = new ArrayList<NvCentre>(nv.centres().size());
        for (var c : nv.centres()) {
            next.add(new NvCentre(c.xMetres(), c.yMetres(), c.zMetres(), axis));
        }
        commitCentres(next);
    }

    private void applyNvShotsEdit() {
        if (suppressFormListeners) return;
        if (!(document.substance() instanceof NvEnsemble nv)) return;
        try {
            long seed       = parseLong(nvSeedField.getText(), nv.shotSeed());
            double threshNm = parseDouble(nvThresholdNmField.getText(), nv.interactionThresholdMetres() * 1e9);
            if (!(threshNm >= 0)) { setStatus("Cut-off must be ≥ 0", true); return; }
            var nextSubstance = new NvEnsemble(
                nv.arrayGeometry(), nv.physics(), seed, threshNm * 1e-9);
            if (Objects.equals(nextSubstance, nv)) return;
            editor.apply(d -> d.withSubstance(nextSubstance), "Edit NV shots");
            setStatus("", false);
        } catch (RuntimeException ex) {
            setStatus(ex.getMessage(), true);
        }
    }

    private void applyOverlayEigenfield() {
        if (suppressFormListeners) return;
        var opt = eigenfieldCombo.getValue();
        if (opt == null || opt.doc() == null) {
            scatter.overlayScriptProperty().set(null);
            return;
        }
        try {
            EigenfieldScript script = EigenfieldEngine.compile(opt.doc().script());
            scatter.overlayScriptProperty().set(script);
            setStatus("", false);
        } catch (Exception ex) {
            scatter.overlayScriptProperty().set(null);
            setStatus("Overlay compile failed: " + ex.getMessage(), true);
        }
    }

    private void refreshEigenfieldList() {
        var prev = eigenfieldCombo.getValue();
        suppressFormListeners = true;
        try {
            eigenfieldCombo.getItems().setAll();
            eigenfieldCombo.getItems().add(new EigenfieldOption(null, "(none)"));
            var state = paneContext.session().project.project();
            if (state != null) {
                for (var id : state.eigenfieldIds()) {
                    var doc = state.eigenfield(id);
                    if (doc != null) {
                        eigenfieldCombo.getItems().add(new EigenfieldOption(doc, doc.name()));
                    }
                }
            }
            // Restore selection by id if possible.
            if (prev != null && prev.doc() != null) {
                for (var item : eigenfieldCombo.getItems()) {
                    if (item.doc() != null && item.doc().id().equals(prev.doc().id())) {
                        eigenfieldCombo.setValue(item);
                        return;
                    }
                }
            }
            eigenfieldCombo.getSelectionModel().select(0);
        } finally {
            suppressFormListeners = false;
        }
    }

    private record EigenfieldOption(EigenfieldDocument doc, String label) {
        @Override public String toString() { return label; }
    }

    /* ── Stamps ────────────────────────────────────────────────────────── */

    private void stampLinear(int n, double lengthM, double zM) {
        var axis = currentAxis();
        var out = new ArrayList<NvCentre>(n);
        for (int i = 0; i < n; i++) {
            double x = -lengthM / 2 + (n == 1 ? 0 : lengthM * i / (n - 1));
            out.add(new NvCentre(x, 0, zM, axis));
        }
        commitCentres(out);
    }

    private void stampGrid(int nx, int ny, double lengthM, double zM) {
        var axis = currentAxis();
        var out = new ArrayList<NvCentre>(nx * ny);
        for (int ix = 0; ix < nx; ix++) {
            double x = -lengthM / 2 + (nx == 1 ? 0 : lengthM * ix / (nx - 1));
            for (int iy = 0; iy < ny; iy++) {
                double y = -lengthM / 2 + (ny == 1 ? 0 : lengthM * iy / (ny - 1));
                out.add(new NvCentre(x, y, zM, axis));
            }
        }
        commitCentres(out);
    }

    private void stampRandom(int n, double lengthM, double zM, long seed) {
        var axis = currentAxis();
        var rng = new Random(seed);
        var out = new ArrayList<NvCentre>(n);
        for (int i = 0; i < n; i++) {
            double x = (rng.nextDouble() - 0.5) * lengthM;
            double y = (rng.nextDouble() - 0.5) * lengthM;
            // Random depth jitter ±10 nm so the dots aren't perfectly coplanar.
            double z = zM + (rng.nextDouble() - 0.5) * 20e-9;
            out.add(new NvCentre(x, y, z, axis));
        }
        commitCentres(out);
    }

    /* ── Status / lifecycle ───────────────────────────────────────────── */

    private void setStatus(String msg, boolean error) {
        if (msg == null || msg.isBlank()) {
            refreshStatusBar();
            return;
        }
        statusBar.getChildren().clear();
        var label = new Label((error ? "⚠ " : "") + msg);
        label.setStyle(error
            ? "-fx-text-fill: #b34646;"
            : "-fx-text-fill: #4a525c;");
        statusBar.getChildren().add(label);
    }

    public void setOnTitleChanged(Runnable cb) { this.onTitleChanged = cb; }
    private void notifyTitleChanged() { if (onTitleChanged != null) Platform.runLater(onTitleChanged); }

    public SubstanceDocument currentDocument() { return document; }

    /** Test accessor for the embedded 3-D canvas. */
    NvScatter3DCanvas scatterCanvasForTest() { return scatter; }

    public void dispose() {
        scatter.stop();
    }

    /* ── Static helpers ───────────────────────────────────────────────── */

    private static double parseDouble(String text, double fallback) {
        if (text == null || text.isBlank()) return fallback;
        try { return Double.parseDouble(text.trim()); } catch (Exception ex) { return fallback; }
    }

    private static long parseLong(String text, long fallback) {
        if (text == null || text.isBlank()) return fallback;
        try { return Long.parseLong(text.trim()); } catch (Exception ex) { return fallback; }
    }
}
