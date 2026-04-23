package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import javafx.beans.InvalidationListener;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Pannable, zoomable schematic canvas. Owns rendering, mouse handling, and
 * the tool state machine — drop it into any parent and drive it via its
 * {@link CircuitEditSession}.
 *
 * <h3>Tool modes</h3>
 * <ul>
 *   <li><b>IDLE</b> — hover, click to select, drag components.</li>
 *   <li><b>PLACING</b> — a pending component waits; click the canvas to drop it.</li>
 *   <li><b>WIRING</b> — a terminal is armed; click another terminal to finish.</li>
 * </ul>
 *
 * <h3>Callbacks</h3>
 * External consumers hook up listeners for context menus and double-click
 * jumps (see {@link #setContextMenuRequested(BiConsumer)},
 * {@link #setOnComponentActivated(Consumer)}).
 */
public final class SchematicCanvas extends Canvas {
    /** Radius around a terminal centre that counts as a hit. */
    private static final double TERMINAL_HIT_RADIUS = 9;

    private static final Color BG = Color.web("#fafbfc");
    private static final Color GRID_DOT = Color.web("#d4dae0");
    private static final Color WIRE = Color.web("#1f2933");
    private static final Color WIRE_PENDING = Color.web("#1976d2");
    private static final Color WIRE_SELECTED = Color.web("#1976d2");

    private final CircuitEditSession session;

    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;

    private double lastMouseSceneX;
    private double lastMouseSceneY;
    private double lastMouseWorldX;
    private double lastMouseWorldY;

    private ComponentId hoveredComponent;
    private Hit hoveredTerminal;
    private ComponentId draggingComponent;
    private boolean panningInProgress;
    private double dragStartWorldX, dragStartWorldY;
    private double dragStartCompX, dragStartCompY;

    private ToolState tool = ToolState.idle();
    private final javafx.beans.property.ObjectProperty<PrimaryMode> primaryMode =
        new javafx.beans.property.SimpleObjectProperty<>(PrimaryMode.SELECT);

    private BiConsumer<Hit, javafx.scene.input.MouseEvent> contextMenuHandler;
    private Consumer<ComponentId> onComponentActivated;
    private Runnable onEmptyCanvasActivated;

    public SchematicCanvas(CircuitEditSession session) {
        this.session = session;
        setFocusTraversable(true);

        session.current.addListener((obs, oldVal, newVal) -> redraw());
        session.revision.addListener((obs, oldVal, newVal) -> redraw());
        InvalidationListener selectionListener = o -> redraw();
        session.selectedComponents.addListener(selectionListener);
        session.selectedWires.addListener(selectionListener);

        setOnMouseMoved(this::onMouseMoved);
        setOnMouseDragged(this::onMouseDragged);
        setOnMousePressed(this::onMousePressed);
        setOnMouseReleased(this::onMouseReleased);
        setOnMouseClicked(this::onMouseClicked);
        setOnScroll(this::onScroll);
        setOnKeyPressed(this::onKeyPressed);
        setOnContextMenuRequested(evt -> {
            if (contextMenuHandler != null) {
                var hit = hitTest(evt.getX(), evt.getY());
                contextMenuHandler.accept(hit, null);
            }
        });

        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    // ───────── API ─────────

    public void setContextMenuRequested(BiConsumer<Hit, javafx.scene.input.MouseEvent> handler) {
        this.contextMenuHandler = handler;
    }

    public void setOnComponentActivated(Consumer<ComponentId> onActivate) {
        this.onComponentActivated = onActivate;
    }

    public void setOnEmptyCanvasActivated(Runnable action) {
        this.onEmptyCanvasActivated = action;
    }

    public void setTool(ToolState state) {
        this.tool = state == null ? ToolState.idle() : state;
        refreshCursor();
        redraw();
    }

    public ToolState tool() { return tool; }

    public javafx.beans.property.ObjectProperty<PrimaryMode> primaryModeProperty() { return primaryMode; }
    public PrimaryMode primaryMode() { return primaryMode.get(); }

    public void setPrimaryMode(PrimaryMode mode) {
        primaryMode.set(mode == null ? PrimaryMode.SELECT : mode);
        if (tool.kind() == ToolState.Kind.IDLE) refreshCursor();
    }

    private void refreshCursor() {
        setCursor(switch (tool.kind()) {
            case PLACING, WIRING -> Cursor.CROSSHAIR;
            case IDLE -> switch (primaryMode.get()) {
                case SELECT -> Cursor.DEFAULT;
                case PAN -> Cursor.OPEN_HAND;
                case WIRE -> Cursor.CROSSHAIR;
            };
        });
    }

    public void zoomBy(double factor) {
        zoomAround(factor, getWidth() / 2, getHeight() / 2);
    }

    public void resetZoom() {
        scale = 1.0;
        offsetX = 0;
        offsetY = 0;
        redraw();
    }

    /** Pan and scale so the document's bounding box fits inside the canvas with a margin. */
    public void fitToView() {
        var doc = session.doc();
        if (doc.components().isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            resetZoom();
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (var c : doc.components()) {
            var pos = doc.layout().positionOf(c.id()).orElse(null);
            if (pos == null) continue;
            var geom = ComponentGeometry.of(c);
            minX = Math.min(minX, pos.x() - geom.halfWidth());
            minY = Math.min(minY, pos.y() - geom.halfHeight());
            maxX = Math.max(maxX, pos.x() + geom.halfWidth());
            maxY = Math.max(maxY, pos.y() + geom.halfHeight());
        }
        double margin = 60;
        double worldWidth = Math.max(1, maxX - minX);
        double worldHeight = Math.max(1, maxY - minY);
        double sx = (getWidth() - 2 * margin) / worldWidth;
        double sy = (getHeight() - 2 * margin) / worldHeight;
        scale = Math.max(0.1, Math.min(3.0, Math.min(sx, sy)));
        offsetX = margin - minX * scale + (getWidth() - 2 * margin - worldWidth * scale) / 2;
        offsetY = margin - minY * scale + (getHeight() - 2 * margin - worldHeight * scale) / 2;
        redraw();
    }

    private void zoomAround(double factor, double cx, double cy) {
        double newScale = Math.max(0.1, Math.min(4.0, scale * factor));
        double worldX = (cx - offsetX) / scale;
        double worldY = (cy - offsetY) / scale;
        scale = newScale;
        offsetX = cx - worldX * scale;
        offsetY = cy - worldY * scale;
        redraw();
    }

    public void redraw() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        GraphicsContext g = getGraphicsContext2D();
        g.save();
        g.setFill(BG);
        g.fillRect(0, 0, getWidth(), getHeight());

        g.translate(offsetX, offsetY);
        g.scale(scale, scale);

        drawGrid(g);
        drawWires(g);
        drawComponents(g);
        drawPendingWireOrPlacement(g);
        g.restore();
    }

    // ───────── Drawing ─────────

    private void drawGrid(GraphicsContext g) {
        double viewLeft = -offsetX / scale;
        double viewTop = -offsetY / scale;
        double viewRight = viewLeft + getWidth() / scale;
        double viewBottom = viewTop + getHeight() / scale;

        int step = 20;
        double startX = Math.floor(viewLeft / step) * step;
        double startY = Math.floor(viewTop / step) * step;
        g.setFill(GRID_DOT);
        for (double x = startX; x <= viewRight + step; x += step) {
            for (double y = startY; y <= viewBottom + step; y += step) {
                g.fillOval(x - 1, y - 1, 2, 2);
            }
        }
    }

    private void drawWires(GraphicsContext g) {
        var doc = session.doc();
        for (var w : doc.wires()) {
            var from = terminalWorld(w.from());
            var to = terminalWorld(w.to());
            if (from == null || to == null) continue;
            boolean selected = session.selectedWires.contains(w.id());
            g.setStroke(selected ? WIRE_SELECTED : WIRE);
            g.setLineWidth(selected ? 2.4 : 1.6);
            var path = WireRouter.route(from[0], from[1], to[0], to[1]);
            double[] xs = new double[path.size()];
            double[] ys = new double[path.size()];
            for (int i = 0; i < path.size(); i++) { xs[i] = path.get(i)[0]; ys[i] = path.get(i)[1]; }
            g.strokePolyline(xs, ys, xs.length);
        }
    }

    private void drawComponents(GraphicsContext g) {
        var doc = session.doc();
        for (var c : doc.components()) {
            var pos = doc.layout().positionOf(c.id()).orElse(null);
            if (pos == null) continue;
            boolean selected = session.selectedComponents.contains(c.id());
            boolean hovered = c.id().equals(hoveredComponent)
                && (hoveredTerminal == null || !hoveredTerminal.kind().isTerminal());
            ComponentRenderer.draw(g, c, pos.x(), pos.y(), selected, hovered);
        }
    }

    private void drawPendingWireOrPlacement(GraphicsContext g) {
        if (tool.kind() == ToolState.Kind.WIRING) {
            var from = terminalWorld(tool.wireStart());
            if (from != null) {
                g.setStroke(WIRE_PENDING);
                g.setLineWidth(1.6);
                g.setLineDashes(4, 3);
                var path = WireRouter.route(from[0], from[1], lastMouseWorldX, lastMouseWorldY);
                double[] xs = new double[path.size()];
                double[] ys = new double[path.size()];
                for (int i = 0; i < path.size(); i++) { xs[i] = path.get(i)[0]; ys[i] = path.get(i)[1]; }
                g.strokePolyline(xs, ys, xs.length);
                g.setLineDashes();
            }
        } else if (tool.kind() == ToolState.Kind.PLACING) {
            // Phantom: dim preview of the component at cursor location
            var prototype = tool.placementPrototype();
            if (prototype != null) {
                g.setGlobalAlpha(0.45);
                ComponentRenderer.draw(g, prototype, lastMouseWorldX, lastMouseWorldY, false, false);
                g.setGlobalAlpha(1);
            }
        }
    }

    // ───────── Hit testing ─────────

    private double[] terminalWorld(ComponentTerminal terminal) {
        var comp = session.doc().component(terminal.componentId()).orElse(null);
        if (comp == null) return null;
        var pos = session.doc().layout().positionOf(terminal.componentId()).orElse(null);
        if (pos == null) return null;
        var geom = ComponentGeometry.of(comp);
        var term = geom.terminal(terminal.port());
        return new double[]{pos.x() + term.xOffset(), pos.y() + term.yOffset()};
    }

    /** Determine what's under a canvas-space point (scene coords). */
    public Hit hitTest(double canvasX, double canvasY) {
        double worldX = (canvasX - offsetX) / scale;
        double worldY = (canvasY - offsetY) / scale;
        var doc = session.doc();
        // Terminals first so they beat component bodies.
        for (var c : doc.components()) {
            var pos = doc.layout().positionOf(c.id()).orElse(null);
            if (pos == null) continue;
            var geom = ComponentGeometry.of(c);
            for (var t : geom.terminals()) {
                double tx = pos.x() + t.xOffset();
                double ty = pos.y() + t.yOffset();
                if (Math.hypot(worldX - tx, worldY - ty) <= TERMINAL_HIT_RADIUS) {
                    return Hit.terminal(new ComponentTerminal(c.id(), t.port()), tx, ty);
                }
            }
        }
        // Component bodies next.
        for (int i = doc.components().size() - 1; i >= 0; i--) {
            var c = doc.components().get(i);
            var pos = doc.layout().positionOf(c.id()).orElse(null);
            if (pos == null) continue;
            var geom = ComponentGeometry.of(c);
            if (worldX >= pos.x() - geom.halfWidth() && worldX <= pos.x() + geom.halfWidth()
                    && worldY >= pos.y() - geom.halfHeight() && worldY <= pos.y() + geom.halfHeight()) {
                return Hit.component(c.id(), pos.x(), pos.y());
            }
        }
        // Wires last (segment-based hit test with small tolerance).
        for (var w : doc.wires()) {
            var from = terminalWorld(w.from());
            var to = terminalWorld(w.to());
            if (from == null || to == null) continue;
            var path = WireRouter.route(from[0], from[1], to[0], to[1]);
            for (int i = 1; i < path.size(); i++) {
                double[] a = path.get(i - 1);
                double[] b = path.get(i);
                if (pointNearSegment(worldX, worldY, a[0], a[1], b[0], b[1], 6)) {
                    return Hit.wire(w.id(), worldX, worldY);
                }
            }
        }
        return Hit.empty(worldX, worldY);
    }

    private static boolean pointNearSegment(double px, double py, double x1, double y1, double x2, double y2, double tol) {
        double dx = x2 - x1, dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-9) return Math.hypot(px - x1, py - y1) <= tol;
        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
        double cx = x1 + t * dx, cy = y1 + t * dy;
        return Math.hypot(px - cx, py - cy) <= tol;
    }

    // ───────── Mouse ─────────

    private void onMouseMoved(javafx.scene.input.MouseEvent e) {
        lastMouseSceneX = e.getX();
        lastMouseSceneY = e.getY();
        lastMouseWorldX = (e.getX() - offsetX) / scale;
        lastMouseWorldY = (e.getY() - offsetY) / scale;
        var hit = hitTest(e.getX(), e.getY());
        hoveredTerminal = hit.kind().isTerminal() ? hit : null;
        hoveredComponent = hit.kind() == Hit.Kind.COMPONENT ? hit.componentId()
            : hit.kind() == Hit.Kind.TERMINAL ? hit.terminal().componentId() : null;
        redraw();
    }

    private void onMousePressed(javafx.scene.input.MouseEvent e) {
        requestFocus();
        lastMouseSceneX = e.getX();
        lastMouseSceneY = e.getY();
        lastMouseWorldX = (e.getX() - offsetX) / scale;
        lastMouseWorldY = (e.getY() - offsetY) / scale;

        boolean panRequested =
            e.getButton() == MouseButton.MIDDLE
            || (e.getButton() == MouseButton.PRIMARY && (e.isAltDown() || primaryMode.get() == PrimaryMode.PAN));
        if (panRequested) {
            panningInProgress = true;
            setCursor(Cursor.CLOSED_HAND);
            return;
        }

        if (e.getButton() != MouseButton.PRIMARY) return;

        var hit = hitTest(e.getX(), e.getY());
        if (tool.kind() == ToolState.Kind.WIRING) return; // handled on click
        if (tool.kind() == ToolState.Kind.PLACING) return;

        if (primaryMode.get() == PrimaryMode.WIRE && hit.kind() == Hit.Kind.TERMINAL) {
            setTool(ToolState.wiring(hit.terminal()));
            return;
        }

        if (hit.kind() == Hit.Kind.COMPONENT) {
            draggingComponent = hit.componentId();
            dragStartWorldX = lastMouseWorldX;
            dragStartWorldY = lastMouseWorldY;
            var pos = session.positionOf(draggingComponent);
            dragStartCompX = pos.x();
            dragStartCompY = pos.y();
            if (!e.isShiftDown()) session.selectOnly(draggingComponent);
            else session.selectedComponents.add(draggingComponent);
        } else if (hit.kind() == Hit.Kind.WIRE) {
            if (!e.isShiftDown()) session.selectWireOnly(hit.wireId());
            else session.selectedWires.add(hit.wireId());
        } else if (hit.kind() == Hit.Kind.EMPTY) {
            session.clearSelection();
        }
    }

    private void onMouseDragged(javafx.scene.input.MouseEvent e) {
        double prevSceneX = lastMouseSceneX;
        double prevSceneY = lastMouseSceneY;
        lastMouseSceneX = e.getX();
        lastMouseSceneY = e.getY();
        double worldX = (e.getX() - offsetX) / scale;
        double worldY = (e.getY() - offsetY) / scale;
        lastMouseWorldX = worldX;
        lastMouseWorldY = worldY;
        if (panningInProgress) {
            offsetX += (e.getX() - prevSceneX);
            offsetY += (e.getY() - prevSceneY);
            redraw();
            return;
        }
        if (draggingComponent != null) {
            double dx = worldX - dragStartWorldX;
            double dy = worldY - dragStartWorldY;
            double newX = snap(dragStartCompX + dx);
            double newY = snap(dragStartCompY + dy);
            session.moveComponent(draggingComponent, newX, newY);
        }
    }

    private void onMouseReleased(javafx.scene.input.MouseEvent e) {
        draggingComponent = null;
        if (panningInProgress) {
            panningInProgress = false;
            refreshCursor();
        }
    }

    private void onMouseClicked(javafx.scene.input.MouseEvent e) {
        lastMouseSceneX = e.getX();
        lastMouseSceneY = e.getY();
        lastMouseWorldX = (e.getX() - offsetX) / scale;
        lastMouseWorldY = (e.getY() - offsetY) / scale;
        var hit = hitTest(e.getX(), e.getY());

        if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
            if (hit.kind() == Hit.Kind.COMPONENT && onComponentActivated != null) {
                onComponentActivated.accept(hit.componentId());
                return;
            }
            if (hit.kind() == Hit.Kind.EMPTY && onEmptyCanvasActivated != null) {
                onEmptyCanvasActivated.run();
                return;
            }
        }

        if (tool.kind() == ToolState.Kind.PLACING && hit.kind() == Hit.Kind.EMPTY
                && e.getButton() == MouseButton.PRIMARY) {
            // Placement is handled externally via SchematicPane which interprets ToolState.
            // The canvas just signals where by setting tool to IDLE and leaving it to the
            // containing pane to commit — see SchematicPane.pendingPlacementCommit.
            return;
        }

        if (e.getButton() == MouseButton.PRIMARY) {
            if (tool.kind() == ToolState.Kind.WIRING) {
                if (hit.kind() == Hit.Kind.TERMINAL) {
                    ComponentTerminal start = tool.wireStart();
                    ComponentTerminal end = hit.terminal();
                    if (!start.equals(end) && !session.isWireBetween(start, end)
                            && !start.componentId().equals(end.componentId())) {
                        session.addWire(start, end);
                    }
                    setTool(ToolState.idle());
                } else if (hit.kind() == Hit.Kind.EMPTY) {
                    // Clicking empty cancels wire mode.
                    setTool(ToolState.idle());
                }
            } else if (tool.kind() == ToolState.Kind.IDLE) {
                if (hit.kind() == Hit.Kind.TERMINAL) {
                    setTool(ToolState.wiring(hit.terminal()));
                }
            }
        }
    }

    private void onScroll(javafx.scene.input.ScrollEvent e) {
        // Shortcut+scroll zooms; plain scroll pans so trackpad users aren't stuck.
        if (e.isShortcutDown()) {
            zoomAround(Math.exp(e.getDeltaY() * 0.002), e.getX(), e.getY());
        } else {
            offsetX += e.getDeltaX();
            offsetY += e.getDeltaY();
            redraw();
        }
        e.consume();
    }

    /** Called by the outer pane's scene-level filter so shortcuts work regardless of focus. */
    public boolean handleKey(javafx.scene.input.KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            setTool(ToolState.idle());
            session.clearSelection();
            return true;
        }
        if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            session.deleteSelection();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
            clipboardCopy();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.V) {
            clipboardPaste();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.D) {
            session.duplicateSelection(40, 40);
            return true;
        }
        if (e.isShortcutDown() && (e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.EQUALS)) {
            zoomBy(1.2);
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.MINUS) {
            zoomBy(1.0 / 1.2);
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.DIGIT0) {
            resetZoom();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
            fitToView();
            return true;
        }
        return false;
    }

    private void onKeyPressed(javafx.scene.input.KeyEvent e) {
        if (handleKey(e)) e.consume();
    }

    private void clipboardCopy() {
        pendingClipboard = session.selectedComponents.stream().toList();
    }

    private void clipboardPaste() {
        if (pendingClipboard == null || pendingClipboard.isEmpty()) return;
        session.selectedComponents.clear();
        session.selectedComponents.addAll(pendingClipboard);
        session.duplicateSelection(40, 40);
    }

    private java.util.List<ComponentId> pendingClipboard = java.util.List.of();

    private static double snap(double v) {
        int step = 20;
        return Math.round(v / step) * step;
    }

    public double[] cursorWorld() {
        return new double[]{lastMouseWorldX, lastMouseWorldY};
    }

    // ───────── Types ─────────

    public record Hit(Kind kind, ComponentId componentId, ComponentTerminal terminal, String wireId, double worldX, double worldY) {
        public enum Kind {
            EMPTY, COMPONENT, TERMINAL, WIRE;
            public boolean isTerminal() { return this == TERMINAL; }
        }

        public static Hit empty(double x, double y) { return new Hit(Kind.EMPTY, null, null, null, x, y); }
        public static Hit component(ComponentId id, double x, double y) { return new Hit(Kind.COMPONENT, id, null, null, x, y); }
        public static Hit terminal(ComponentTerminal t, double x, double y) { return new Hit(Kind.TERMINAL, t.componentId(), t, null, x, y); }
        public static Hit wire(String wireId, double x, double y) { return new Hit(Kind.WIRE, null, null, wireId, x, y); }
    }

    /** Tool state machine. */
    public record ToolState(Kind kind, ComponentTerminal wireStart, CircuitComponent placementPrototype) {
        public enum Kind { IDLE, PLACING, WIRING }

        public static ToolState idle() { return new ToolState(Kind.IDLE, null, null); }
        public static ToolState wiring(ComponentTerminal start) { return new ToolState(Kind.WIRING, start, null); }
        public static ToolState placing(CircuitComponent prototype) { return new ToolState(Kind.PLACING, null, prototype); }
    }

    /** Primary interaction mode the user picks from the toolbar. */
    public enum PrimaryMode {
        SELECT("Select", "V"),
        PAN("Pan", "H"),
        WIRE("Wire", "W");

        private final String label;
        private final String shortcut;

        PrimaryMode(String label, String shortcut) {
            this.label = label;
            this.shortcut = shortcut;
        }

        public String label() { return label; }
        public String shortcut() { return shortcut; }
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return getWidth(); }
    @Override public double prefHeight(double w) { return getHeight(); }
    @Override public double minWidth(double h) { return 0; }
    @Override public double minHeight(double w) { return 0; }
}
