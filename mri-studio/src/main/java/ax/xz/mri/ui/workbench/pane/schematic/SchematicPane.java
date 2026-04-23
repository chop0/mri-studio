package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.starter.AutoLayout;
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

        // Scene-level filter: keyboard shortcuts work whenever the pointer is
        // over the schematic pane, no focus wrestling.
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        canvas.setContextMenuRequested((hit, evt) -> showContextMenu(hit));
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
            var prototype = canvas.tool().placementPrototype();
            if (prototype == null) return;
            var cursor = canvas.cursorWorld();
            var position = new ax.xz.mri.model.circuit.ComponentPosition(
                prototype.id(), snap(cursor[0]), snap(cursor[1]), 0);
            session.addComponent(prototype, position);
            canvas.setTool(SchematicCanvas.ToolState.idle());
        });
    }

    public SchematicCanvas canvas() { return canvas; }

    public void replaceDocument(CircuitDocument document) {
        session.replaceDocument(document);
    }

    /** Route key events (pressed anywhere over this pane) into the canvas or mode hotkeys. */
    private void onKey(KeyEvent e) {
        if (e.isConsumed()) return;
        if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
        // Mode hotkeys first — single-letter, no modifier.
        if (!e.isShortcutDown() && !e.isAltDown() && !e.isMetaDown()) {
            if (e.getCode() == KeyCode.V) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.SELECT); e.consume(); return; }
            if (e.getCode() == KeyCode.H) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.PAN); e.consume(); return; }
            if (e.getCode() == KeyCode.W) { canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.WIRE); e.consume(); return; }
        }
        if (canvas.handleKey(e)) e.consume();
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
        hint.setText(components + " components \u00b7 " + wires + " wires");
    }

    private void armPlacement(CircuitComponent component) {
        canvas.setTool(SchematicCanvas.ToolState.placing(component));
        session.statusMessage.set("Click on the canvas to place " + component.name() + ". Press Esc to cancel.");
    }

    private void showContextMenu(SchematicCanvas.Hit hit) {
        var menu = new ContextMenu();
        switch (hit.kind()) {
            case COMPONENT -> populateComponentMenu(menu, hit);
            case WIRE -> populateWireMenu(menu, hit);
            case TERMINAL -> populateTerminalMenu(menu, hit);
            case EMPTY -> populateAddMenu(menu, hit);
        }
        menu.show(canvas, canvas.localToScreen(hit.worldX(), hit.worldY()).getX(),
            canvas.localToScreen(hit.worldX(), hit.worldY()).getY());
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
        addMenu(menu, "Voltage source", () -> new CircuitComponent.VoltageSource(
            newId("src"), uniqueName("V"),
            ax.xz.mri.model.simulation.AmplitudeKind.REAL, 0, 0, 1, 0), hit);
        addMenu(menu, "Switch", () -> new CircuitComponent.SwitchComponent(
            newId("sw"), uniqueName("SW"), 0.5, 1e9, 0.5), hit);
        addMenu(menu, "Coil", () -> new CircuitComponent.Coil(
            newId("coil"), uniqueName("Coil"), null, 0, 0), hit);
        addMenu(menu, "Probe", () -> new CircuitComponent.Probe(
            newId("probe"), uniqueName("Probe"), 1, 0, Double.POSITIVE_INFINITY), hit);
        menu.getItems().add(new SeparatorMenuItem());
        addMenu(menu, "Resistor", () -> new CircuitComponent.Resistor(
            newId("r"), uniqueName("R"), 50), hit);
        addMenu(menu, "Capacitor", () -> new CircuitComponent.Capacitor(
            newId("c"), uniqueName("C"), 1e-9), hit);
        addMenu(menu, "Inductor", () -> new CircuitComponent.Inductor(
            newId("l"), uniqueName("L"), 1e-6), hit);
        menu.getItems().add(new SeparatorMenuItem());
        addMenu(menu, "Ground", () -> new CircuitComponent.Ground(newId("gnd"), uniqueName("GND")), hit);
    }

    private void addMenu(ContextMenu menu, String label, java.util.function.Supplier<CircuitComponent> factory,
                         SchematicCanvas.Hit hit) {
        var item = new MenuItem("Add " + label);
        item.setOnAction(e -> {
            var component = factory.get();
            var position = new ax.xz.mri.model.circuit.ComponentPosition(
                component.id(), snap(hit.worldX()), snap(hit.worldY()), 0);
            session.addComponent(component, position);
        });
        menu.getItems().add(item);
    }

    private static ax.xz.mri.model.circuit.ComponentId newId(String prefix) {
        return new ax.xz.mri.model.circuit.ComponentId(prefix + "-" + java.util.UUID.randomUUID());
    }

    private String uniqueName(String base) {
        var existing = new java.util.HashSet<String>();
        for (var c : session.doc().components()) existing.add(c.name());
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
    }

    private static double snap(double v) {
        return Math.round(v / 20.0) * 20.0;
    }
}
