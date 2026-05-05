package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.util.MathUtil;
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
    /** Amber used by the path-highlight overlay (matches ComponentRenderer.HIGHLIGHT). */
    private static final Color WIRE_HIGHLIGHT = Color.web("#f59e0b");
    /** Soft amber underlay so highlighted wires read clearly even when overlapping. */
    private static final Color WIRE_HIGHLIGHT_GLOW = Color.web("#f59e0b").deriveColor(0, 1, 1, 0.35);

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

    private BiConsumer<Hit, ContextMenuRequest> contextMenuHandler;
    private Consumer<ComponentId> onComponentActivated;
    private Runnable onEmptyCanvasActivated;

    public record ContextMenuRequest(double screenX, double screenY) {}

    public SchematicCanvas(CircuitEditSession session) {
        this.session = session;
        setFocusTraversable(true);

        session.current().addListener((obs, oldVal, newVal) -> redraw());
        session.revision.addListener((obs, oldVal, newVal) -> redraw());
        InvalidationListener selectionListener = o -> redraw();
        session.selectedComponents.addListener(selectionListener);
        session.selectedWires.addListener(selectionListener);
        session.highlightedComponents.addListener(selectionListener);
        session.highlightedWires.addListener(selectionListener);

        setOnMouseMoved(this::onMouseMoved);
        setOnMouseDragged(this::onMouseDragged);
        setOnMousePressed(this::onMousePressed);
        setOnMouseReleased(this::onMouseReleased);
        setOnMouseClicked(this::onMouseClicked);
        setOnScroll(this::onScroll);
        setOnKeyPressed(this::onKeyPressed);
        // Grab focus whenever the pointer is over the canvas so Delete/Esc work
        // even when the user hasn't clicked first.
        setOnMouseEntered(e -> requestFocus());
        setOnContextMenuRequested(evt -> {
            if (contextMenuHandler != null) {
                var hit = hitTest(evt.getX(), evt.getY());
                contextMenuHandler.accept(hit, new ContextMenuRequest(evt.getScreenX(), evt.getScreenY()));
                evt.consume();
            }
        });

        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    // ───────── API ─────────

    public void setContextMenuRequested(BiConsumer<Hit, ContextMenuRequest> handler) {
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

    public void undo() { session.undo(); }
    public void redo() { session.redo(); }

    public void resetZoom() {
        scale = 1.0;
        offsetX = 0;
        offsetY = 0;
        redraw();
    }

    /** Pan and scale so the document's bounding box fits inside the canvas with a margin. */
    public void fitToView() {
        fitToBoundsOf(session.doc().components().stream().map(c -> c.id()).toList());
    }

    /**
     * Pan and scale to fit the current path-highlight overlay
     * ({@link CircuitEditSession#highlightedComponents}). No-op if the
     * overlay is empty.
     */
    public void fitToHighlight() {
        if (session.highlightedComponents.isEmpty()) return;
        fitToBoundsOf(session.highlightedComponents);
    }

    /**
     * Pan and scale so the union of the given components' bounding boxes fits
     * inside the canvas with a margin. Falls back to {@link #resetZoom()} if
     * the set is empty or the canvas isn't laid out yet.
     */
    private void fitToBoundsOf(java.util.Collection<ComponentId> ids) {
        var doc = session.doc();
        if (ids.isEmpty() || doc.components().isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            resetZoom();
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (var id : ids) {
            var c = doc.component(id).orElse(null);
            if (c == null) continue;
            var pos = doc.layout().positionOf(id).orElse(null);
            if (pos == null) continue;
            var geom = ComponentGeometry.of(c);
            minX = Math.min(minX, pos.x() - geom.halfWidth());
            minY = Math.min(minY, pos.y() - geom.halfHeight());
            maxX = Math.max(maxX, pos.x() + geom.halfWidth());
            maxY = Math.max(maxY, pos.y() + geom.halfHeight());
            any = true;
        }
        if (!any) { resetZoom(); return; }
        double margin = 60;
        double worldWidth = Math.max(1, maxX - minX);
        double worldHeight = Math.max(1, maxY - minY);
        double sx = (getWidth() - 2 * margin) / worldWidth;
        double sy = (getHeight() - 2 * margin) / worldHeight;
        scale = MathUtil.clamp(Math.min(sx, sy), 0.1, 3.0);
        offsetX = margin - minX * scale + (getWidth() - 2 * margin - worldWidth * scale) / 2;
        offsetY = margin - minY * scale + (getHeight() - 2 * margin - worldHeight * scale) / 2;
        redraw();
    }

    private void zoomAround(double factor, double cx, double cy) {
        double newScale = MathUtil.clamp(scale * factor, 0.1, 4.0);
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
        // Pass 1: amber underlay for highlighted wires so they read clearly
        // even when crossed by other wires drawn afterwards.
        for (var w : doc.wires()) {
            if (!session.highlightedWires.contains(w.id())) continue;
            var from = terminalWorld(w.from());
            var to = terminalWorld(w.to());
            if (from == null || to == null) continue;
            var path = WireRouter.route(from[0], from[1], to[0], to[1]);
            double[] xs = new double[path.size()];
            double[] ys = new double[path.size()];
            for (int i = 0; i < path.size(); i++) { xs[i] = path.get(i)[0]; ys[i] = path.get(i)[1]; }
            g.setStroke(WIRE_HIGHLIGHT_GLOW);
            g.setLineWidth(7.0);
            g.strokePolyline(xs, ys, xs.length);
        }
        // Pass 2: regular wires.
        for (var w : doc.wires()) {
            var from = terminalWorld(w.from());
            var to = terminalWorld(w.to());
            if (from == null || to == null) continue;
            boolean selected = session.selectedWires.contains(w.id());
            boolean highlighted = session.highlightedWires.contains(w.id());
            Color stroke = selected ? WIRE_SELECTED : highlighted ? WIRE_HIGHLIGHT : WIRE;
            g.setStroke(stroke);
            g.setLineWidth(selected ? 2.4 : highlighted ? 2.4 : 1.6);
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
            boolean highlighted = session.highlightedComponents.contains(c.id());
            ComponentRenderer.draw(g, c, pos, selected, hovered, highlighted);
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
            var placement = tool.placement();
            if (placement != null && !placement.components().isEmpty()) {
                g.setGlobalAlpha(0.45);
                double anchorX = snap(lastMouseWorldX);
                double anchorY = snap(lastMouseWorldY);
                int rot = placement.rotationQuarters();
                boolean mirror = placement.mirrored();
                for (int i = 0; i < placement.components().size(); i++) {
                    var comp = placement.components().get(i);
                    var rel = placement.relativePositions().get(i);
                    double[] off = rotateOffset(rel.x(), rel.y(), rot, mirror);
                    int finalRot = (rel.rotationQuarters() + rot) & 3;
                    boolean finalMirror = rel.mirrored() ^ mirror;
                    var phantomPos = new ax.xz.mri.model.circuit.ComponentPosition(
                        comp.id(), anchorX + off[0], anchorY + off[1], finalRot, finalMirror);
                    ComponentRenderer.draw(g, comp, phantomPos, false, false);
                }
                g.setGlobalAlpha(1);
            }
        }
    }

    /** Apply the cluster's rotation + mirror to a relative offset. */
    private static double[] rotateOffset(double x, double y, int quarters, boolean mirror) {
        int q = ((quarters % 4) + 4) % 4;
        double rx = x, ry = y;
        for (int i = 0; i < q; i++) {
            double nx = -ry;
            double ny = rx;
            rx = nx; ry = ny;
        }
        if (mirror) rx = -rx;
        return new double[]{rx, ry};
    }

    // ───────── Hit testing ─────────

    private double[] terminalWorld(ComponentTerminal terminal) {
        var comp = session.doc().component(terminal.componentId()).orElse(null);
        if (comp == null) return null;
        var pos = session.doc().layout().positionOf(terminal.componentId()).orElse(null);
        if (pos == null) return null;
        var geom = ComponentGeometry.of(comp);
        var term = geom.terminal(terminal.port());
        var xform = pos.transformOffset(term.xOffset(), term.yOffset());
        return new double[]{pos.x() + xform[0], pos.y() + xform[1]};
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
                var xform = pos.transformOffset(t.xOffset(), t.yOffset());
                double tx = pos.x() + xform[0];
                double ty = pos.y() + xform[1];
                if (Math.hypot(worldX - tx, worldY - ty) <= TERMINAL_HIT_RADIUS) {
                    return Hit.terminal(new ComponentTerminal(c.id(), t.port()), tx, ty);
                }
            }
        }
        // Component bodies next. Rotation can make width/height swap; 90/270 turns
        // swap the effective bounding box.
        for (int i = doc.components().size() - 1; i >= 0; i--) {
            var c = doc.components().get(i);
            var pos = doc.layout().positionOf(c.id()).orElse(null);
            if (pos == null) continue;
            var geom = ComponentGeometry.of(c);
            boolean sideways = pos.rotationQuarters() % 2 == 1;
            double hw = sideways ? geom.halfHeight() : geom.halfWidth();
            double hh = sideways ? geom.halfWidth() : geom.halfHeight();
            if (worldX >= pos.x() - hw && worldX <= pos.x() + hw
                    && worldY >= pos.y() - hh && worldY <= pos.y() + hh) {
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
        double t = MathUtil.clamp01(((px - x1) * dx + (py - y1) * dy) / lenSq);
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
            // Coalesce every mouse-move into a single undo entry.
            session.beginTransaction("Move component");
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
        if (draggingComponent != null) {
            draggingComponent = null;
            session.endTransaction();
        }
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
            session.copySelection();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.X) {
            session.cutSelection();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.V) {
            beginClipboardPlacement();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.D) {
            session.duplicateSelection(40, 40);
            return true;
        }
        // Rotation / mirror:
        //   while placing  → rotate the phantom cluster that follows the cursor,
        //                    so the user can orient a new component before dropping it;
        //   otherwise      → rotate the currently selected components.
        if (e.isShortcutDown() && e.getCode() == KeyCode.R) {
            if (tool.kind() == ToolState.Kind.PLACING && tool.placement() != null) {
                tool = new ToolState(tool.kind(), tool.wireStart(),
                    tool.placement().withRotationQuarters(tool.placement().rotationQuarters() + 1));
                redraw();
            } else {
                session.rotateSelection();
            }
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.E) {
            if (tool.kind() == ToolState.Kind.PLACING && tool.placement() != null) {
                tool = new ToolState(tool.kind(), tool.wireStart(),
                    tool.placement().withMirrored(!tool.placement().mirrored()));
                redraw();
            } else {
                session.mirrorSelection();
            }
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
            if (e.isShiftDown()) session.redo();
            else session.undo();
            return true;
        }
        if (e.isShortcutDown() && e.getCode() == KeyCode.Y) {
            session.redo();
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

    /**
     * Turn the session's clipboard into a cluster-placement tool state so
     * the user drops the pasted sub-circuit wherever they click. The
     * cluster's relative positions are anchored to the clipboard's
     * bounding-box top-left corner.
     */
    private void beginClipboardPlacement() {
        var clip = session.clipboardContents();
        if (clip == null || clip.isEmpty()) return;
        double anchorX = Double.POSITIVE_INFINITY;
        double anchorY = Double.POSITIVE_INFINITY;
        for (var p : clip.positions()) {
            if (p.x() < anchorX) anchorX = p.x();
            if (p.y() < anchorY) anchorY = p.y();
        }
        if (!Double.isFinite(anchorX)) { anchorX = 0; anchorY = 0; }
        var rel = new java.util.ArrayList<ax.xz.mri.model.circuit.ComponentPosition>();
        for (var p : clip.positions()) {
            rel.add(new ax.xz.mri.model.circuit.ComponentPosition(
                p.id(), p.x() - anchorX, p.y() - anchorY, p.rotationQuarters(), p.mirrored()));
        }
        setTool(ToolState.placingCluster(
            clip.components(), java.util.List.copyOf(rel), clip.internalWires()));
        redraw();
    }

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

    /**
     * Tool state machine. {@link Placement} carries the pending cluster for
     * both single-component palette placement and multi-component paste —
     * one path, one rendering, one commit. Rotation and mirror apply to
     * the whole cluster while placing and are stamped onto each component
     * when the user clicks to drop.
     */
    public record ToolState(Kind kind, ComponentTerminal wireStart, Placement placement) {
        public enum Kind { IDLE, PLACING, WIRING }

        /**
         * Pending placement cluster. {@code relativePositions} are anchored
         * at {@code (0, 0)} (the cursor); internal wires are recreated with
         * fresh ids on commit so pasting a sub-circuit keeps its wiring.
         */
        public record Placement(
            java.util.List<CircuitComponent> components,
            java.util.List<ax.xz.mri.model.circuit.ComponentPosition> relativePositions,
            java.util.List<ax.xz.mri.model.circuit.Wire> internalWires,
            int rotationQuarters,
            boolean mirrored
        ) {
            public Placement withRotationQuarters(int q) {
                return new Placement(components, relativePositions, internalWires, q, mirrored);
            }
            public Placement withMirrored(boolean m) {
                return new Placement(components, relativePositions, internalWires, rotationQuarters, m);
            }
        }

        public static ToolState idle() { return new ToolState(Kind.IDLE, null, null); }
        public static ToolState wiring(ComponentTerminal start) { return new ToolState(Kind.WIRING, start, null); }

        /** Single-component palette placement — anchored at the cursor. */
        public static ToolState placing(CircuitComponent prototype) {
            var pos = new ax.xz.mri.model.circuit.ComponentPosition(prototype.id(), 0, 0, 0, false);
            return new ToolState(Kind.PLACING, null,
                new Placement(java.util.List.of(prototype), java.util.List.of(pos),
                    java.util.List.of(), 0, false));
        }

        /**
         * Multi-component placement (paste). The cluster's bounding-box
         * top-left sits at the cursor; wires between cluster members are
         * regenerated with fresh ids on commit.
         */
        public static ToolState placingCluster(
                java.util.List<CircuitComponent> components,
                java.util.List<ax.xz.mri.model.circuit.ComponentPosition> relativePositions,
                java.util.List<ax.xz.mri.model.circuit.Wire> internalWires) {
            return new ToolState(Kind.PLACING, null,
                new Placement(components, relativePositions, internalWires, 0, false));
        }

        /** Back-compat convenience for single-component placement consumers. */
        public CircuitComponent placementPrototype() {
            return placement != null && placement.components().size() == 1
                ? placement.components().get(0) : null;
        }
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
