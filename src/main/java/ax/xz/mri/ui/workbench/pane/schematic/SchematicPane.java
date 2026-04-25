package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Top-level schematic editor: left palette + centre canvas + right inspector.
 *
 * <p>A fresh pane subscribes to the given {@link CircuitEditSession} and
 * rebuilds automatically when the document changes. Double-click on a
 * {@link CircuitComponent.Coil} jumps to the underlying
 * {@link ax.xz.mri.project.EigenfieldDocument} through the {@code
 * onJumpToEigenfield} callback; right-click anywhere surfaces an
 * add-component context menu.
 */
public final class SchematicPane extends BorderPane {
    private final CircuitEditSession session;
    private final SchematicCanvas canvas;
    private final Supplier<ProjectRepository> repositorySupplier;
    private final Consumer<ProjectNodeId> onJumpToEigenfield;

    private double lastSceneMouseX = Double.NaN;
    private double lastSceneMouseY = Double.NaN;
    private javafx.event.EventHandler<KeyEvent> sceneKeyFilter;
    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> sceneMouseFilter;

    public SchematicPane(CircuitEditSession session,
                         Supplier<ProjectRepository> repositorySupplier,
                         Consumer<ProjectNodeId> onJumpToEigenfield) {
        this.session = session;
        this.repositorySupplier = repositorySupplier;
        this.onJumpToEigenfield = onJumpToEigenfield;
        getStyleClass().add("schematic-pane");

        canvas = new SchematicCanvas(session);
        var toolbar = new SchematicToolbar(canvas);
        var canvasHost = new StackPane(canvas);
        canvasHost.getStyleClass().add("schematic-canvas-host");
        canvas.widthProperty().bind(canvasHost.widthProperty());
        canvas.heightProperty().bind(canvasHost.heightProperty());

        var palette = new ComponentPalette(component -> armPlacement(component));
        var paletteScroll = new ScrollPane(palette);
        paletteScroll.setFitToWidth(true);
        paletteScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        paletteScroll.getStyleClass().add("schematic-palette-scroll");

        var inspector = new ComponentInspector(session, repositorySupplier, onJumpToEigenfield);
        var inspectorScroll = new ScrollPane(inspector);
        inspectorScroll.setFitToWidth(true);
        inspectorScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        inspectorScroll.getStyleClass().add("schematic-inspector-scroll");

        var statusBar = buildStatusBar();

        var centre = new BorderPane(canvasHost);
        centre.setTop(toolbar);
        centre.setBottom(statusBar);
        setLeft(paletteScroll);
        setCenter(centre);
        setRight(inspectorScroll);

        // Scene-level filters: the mouse filter tracks cursor position in scene
        // coordinates (so the key filter can tell whether the cursor is over
        // the schematic); the key filter routes shortcut events to the canvas
        // regardless of which descendant has focus.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                if (sceneKeyFilter != null) oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
                if (sceneMouseFilter != null) oldScene.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, sceneMouseFilter);
            }
            if (newScene != null) {
                sceneKeyFilter = this::onKey;
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, sceneKeyFilter);
                sceneMouseFilter = e -> {
                    lastSceneMouseX = e.getSceneX();
                    lastSceneMouseY = e.getSceneY();
                };
                newScene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, sceneMouseFilter);
            } else {
                sceneKeyFilter = null;
                sceneMouseFilter = null;
            }
        });

        canvas.setContextMenuRequested((hit, req) -> showContextMenu(hit, req));
        canvas.setOnComponentActivated(id -> {
            var component = session.componentAt(id);
            if (component instanceof CircuitComponent.Coil coil && coil.eigenfieldId() != null) {
                if (onJumpToEigenfield != null) onJumpToEigenfield.accept(coil.eigenfieldId());
            }
        });

        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, evt -> {
            if (evt.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (canvas.tool().kind() != SchematicCanvas.ToolState.Kind.PLACING) return;
            var hit = canvas.hitTest(evt.getX(), evt.getY());
            if (hit.kind() != SchematicCanvas.Hit.Kind.EMPTY) return;
            var placement = canvas.tool().placement();
            if (placement == null || placement.components().isEmpty()) return;
            var cursor = canvas.cursorWorld();
            double anchorX = snap(cursor[0]);
            double anchorY = snap(cursor[1]);
            session.insertCluster(placement.components(), placement.relativePositions(),
                placement.internalWires(), anchorX, anchorY,
                placement.rotationQuarters(), placement.mirrored());
            canvas.setTool(SchematicCanvas.ToolState.idle());
        });
    }

    public SchematicCanvas canvas() { return canvas; }

    public CircuitEditSession session() { return session; }

    public void replaceDocument(CircuitDocument document) {
        session.loadDocument(document);
    }

    /**
     * Set the path-highlight overlay to the given components and wires, then
     * fit the canvas viewport to that path. Used by the clip inspector's
     * "Show in schematic" affordance to land the user looking right at the
     * route between a clip's source and the coil it eventually feeds.
     *
     * <p>Run on the FX thread; the fit has to happen after the canvas has
     * actually been laid out, so we defer one tick if width/height are zero.
     */
    public void showPathHighlight(java.util.Collection<ax.xz.mri.model.circuit.ComponentId> components,
                                   java.util.Collection<String> wireIds) {
        session.setHighlight(components, wireIds);
        Runnable fit = () -> {
            if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
                javafx.application.Platform.runLater(canvas::fitToHighlight);
            } else {
                canvas.fitToHighlight();
            }
        };
        fit.run();
    }

    /** Drop the highlight overlay (no-op if none is set). */
    public void clearPathHighlight() {
        session.clearHighlight();
    }

    /**
     * Scene-level key filter. Fires when the pointer is over the schematic
     * pane — always, regardless of where focus is (canvas, palette, inspector
     * text field, whatever).
     *
     * <p>When a text input control has focus, the standard text-editing
     * shortcuts ({@link #isTextShortcut Cmd/Ctrl + C / X / V / Z / Y / A})
     * stay local to the field so the user can copy, cut, paste, undo, and
     * select-all within the inspector text normally. Everything else (mode
     * letters, schematic-specific shortcuts like Cmd+D duplicate or Cmd+R
     * rotate) still fires so the schematic is reachable regardless of
     * focus — the common frustration of "I can't undo because the cursor
     * is somewhere".
     */
    private void onKey(KeyEvent e) {
        if (e.isConsumed()) return;
        if (!isPointerOverPane()) return;
        boolean inTextField = e.getTarget() instanceof javafx.scene.control.TextInputControl;
        if (inTextField && isTextShortcut(e)) return;
        if (!e.isShortcutDown() && !e.isAltDown() && !e.isMetaDown()) {
            // Single-letter mode hotkeys collide with typing, so skip them when
            // a text field is focused.
            if (inTextField) return;
            if (e.getCode() == KeyCode.V) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.SELECT); e.consume(); return; }
            if (e.getCode() == KeyCode.H) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.PAN); e.consume(); return; }
            if (e.getCode() == KeyCode.W) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.WIRE); e.consume(); return; }
            if (canvas.handleKey(e)) e.consume();
            return;
        }
        if (canvas.handleKey(e)) e.consume();
    }

    /**
     * True for the standard text-editing shortcuts that a
     * {@link javafx.scene.control.TextInputControl} should always get to
     * handle itself. Cmd/Ctrl + {C, X, V, Z, Y, A}. Schematic-specific
     * shortcuts like Cmd+D (duplicate), Cmd+R (rotate), Cmd+E (mirror),
     * Cmd+F (fit), Cmd+0/+/- (zoom) are NOT text shortcuts and stay on
     * the schematic regardless of focus.
     */
    private static boolean isTextShortcut(KeyEvent e) {
        if (!e.isShortcutDown()) return false;
        return switch (e.getCode()) {
            case C, X, V, Z, Y, A -> true;
            default -> false;
        };
    }

    private boolean isPointerOverPane() {
        if (Double.isNaN(lastSceneMouseX)) return false;
        var bounds = localToScene(getBoundsInLocal());
        return bounds.contains(lastSceneMouseX, lastSceneMouseY);
    }

    private HBox buildStatusBar() {
        var label = new Label();
        label.textProperty().bind(session.statusMessage);
        var hint = new Label();
        session.revision.addListener((obs, o, n) -> refreshHint(hint));
        session.current.addListener((obs, o, n) -> refreshHint(hint));
        refreshHint(hint);
        var spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var bar = new HBox(8, label, spacer, hint);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("schematic-status-bar");
        return bar;
    }

    private void refreshHint(Label hint) {
        var doc = session.doc();
        int components = doc.components().size();
        int wires = doc.wires().size();
        hint.setText(components + " components, " + wires + " wires");
    }

    private void armPlacement(CircuitComponent component) {
        canvas.setTool(SchematicCanvas.ToolState.placing(component));
        session.statusMessage.set("Click on the canvas to place " + component.name() + ". Press Esc to cancel.");
    }

    private void showContextMenu(SchematicCanvas.Hit hit, SchematicCanvas.ContextMenuRequest req) {
        var menu = new ContextMenu();
        switch (hit.kind()) {
            case COMPONENT -> populateComponentMenu(menu, hit);
            case WIRE -> populateWireMenu(menu, hit);
            case TERMINAL -> populateTerminalMenu(menu, hit);
            case EMPTY -> populateAddMenu(menu, hit);
        }
        if (!menu.getItems().isEmpty()) menu.show(canvas, req.screenX(), req.screenY());
    }

    private void populateComponentMenu(ContextMenu menu, SchematicCanvas.Hit hit) {
        var component = session.componentAt(hit.componentId());
        if (component == null) return;
        var delete = new MenuItem("Delete");
        delete.setOnAction(e -> session.apply(doc -> doc.removeComponent(hit.componentId())));
        menu.getItems().add(delete);
        var disconnect = new MenuItem("Disconnect wires");
        disconnect.setOnAction(e -> {
            session.apply(doc -> {
                var nextWires = new java.util.ArrayList<ax.xz.mri.model.circuit.Wire>();
                for (var w : doc.wires()) {
                    if (!w.from().componentId().equals(hit.componentId())
                            && !w.to().componentId().equals(hit.componentId())) nextWires.add(w);
                }
                return doc.withWires(nextWires);
            });
        });
        menu.getItems().add(disconnect);
        if (component instanceof CircuitComponent.Coil coil && coil.eigenfieldId() != null) {
            var edit = new MenuItem("Edit eigenfield");
            edit.setOnAction(e -> onJumpToEigenfield.accept(coil.eigenfieldId()));
            menu.getItems().add(0, edit);
            menu.getItems().add(1, new SeparatorMenuItem());
        }
    }

    private void populateWireMenu(ContextMenu menu, SchematicCanvas.Hit hit) {
        var delete = new MenuItem("Delete wire");
        delete.setOnAction(e -> session.apply(doc -> doc.removeWire(hit.wireId())));
        menu.getItems().add(delete);
    }

    private void populateTerminalMenu(ContextMenu menu, SchematicCanvas.Hit hit) {
        var startWire = new MenuItem("Start wire from here");
        startWire.setOnAction(e -> canvas.setTool(SchematicCanvas.ToolState.wiring(hit.terminal())));
        menu.getItems().add(startWire);
    }

    private void populateAddMenu(ContextMenu menu, SchematicCanvas.Hit hit) {
        String currentSection = null;
        for (var entry : ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters.paletteEntries()) {
            if (currentSection != null && !currentSection.equals(entry.section())) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            currentSection = entry.section();
            var item = new MenuItem("Add " + entry.label());
            item.setOnAction(e -> {
                var component = entry.factory().get();
                var position = new ax.xz.mri.model.circuit.ComponentPosition(
                    component.id(), snap(hit.worldX()), snap(hit.worldY()), 0);
                session.addComponent(component, position);
            });
            menu.getItems().add(item);
        }
    }

    private static double snap(double v) {
        return Math.round(v / 20.0) * 20.0;
    }
}
