package ax.xz.mri.ui.substance;

import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.canvas.Projection;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.util.MathUtil;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Direct-manipulation 3-D viewport for an NV-centre ensemble.
 *
 * <p>Modelled on {@link ax.xz.mri.ui.eigenfield.EigenfieldPreviewCanvas}'s
 * orbit-camera + orthographic projection idiom (so it visually matches every
 * other 3-D surface in the studio), with three additions on top:
 *
 * <ul>
 *   <li>The set of NV centres is an {@link ObservableList} the editor owns —
 *       drag, add, and delete operations mutate the list directly and the
 *       editor's mutation pipeline replays the change into the document.</li>
 *   <li>A {@link NvConstraint} snaps Add-tool clicks and Move drags to a plane
 *       or line. {@link NvConstraint.None} leaves drags free in the camera-aligned
 *       plane at the centre's current depth.</li>
 *   <li>An optional {@link EigenfieldScript} renders a translucent arrow field
 *       in the background so the user sees the field the NVs would sense.</li>
 * </ul>
 *
 * <p>Tool dispatch is driven by an {@link NvEditorTool} property the editor's
 * toolbar binds to. A hover-position {@code Vec3} is exposed for an ANSYS-style
 * status hint at the bottom of the editor pane.
 */
public final class NvScatter3DCanvas extends StackPane {

    /** Half-extent of the viewport box in metres. Default 4 µm matches the NV starter FOV. */
    public static final double DEFAULT_HALF_EXTENT_M = 4e-6;
    /** Hit-test radius around an NV centre projection, in screen pixels. */
    private static final double HIT_RADIUS_PX = 9;

    private final ResizableCanvas canvas = new ResizableCanvas();

    private final ObservableList<NvCentre> centres = FXCollections.observableArrayList();

    private final DoubleProperty theta = new SimpleDoubleProperty(0.6);
    private final DoubleProperty phi   = new SimpleDoubleProperty(0.3);
    private final DoubleProperty zoom  = new SimpleDoubleProperty(1.0);
    private final DoubleProperty halfExtentM = new SimpleDoubleProperty(DEFAULT_HALF_EXTENT_M);

    private final ObjectProperty<EigenfieldScript> overlayScript = new SimpleObjectProperty<>();
    private final IntegerProperty overlaySamples = new SimpleIntegerProperty(7);
    private final ObjectProperty<NvConstraint> constraint = new SimpleObjectProperty<>(new NvConstraint.None());
    private final ObjectProperty<NvEditorTool> activeTool = new SimpleObjectProperty<>(NvEditorTool.SELECT);
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);

    /** Updated continuously as the cursor moves — the editor pane reads it for status hints. */
    private final ObjectProperty<Vec3> hoverWorldPosition = new SimpleObjectProperty<>(Vec3.ZERO);

    /** Hook the editor passes in to commit centre mutations into the document. */
    private Consumer<List<NvCentre>> onCentresMutated;
    /** Hook the editor passes in for right-click context menus. */
    private ContextMenuRequest contextMenuRequest;

    private double dragX, dragY;
    private boolean orbiting;
    private boolean dirty = true;
    private int draggingIndex = -1;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w > 0 && h > 0) paint(canvas.getGraphicsContext2D(), w, h);
        }
    };

    public NvScatter3DCanvas() {
        getChildren().add(canvas);
        canvas.setOnResized(this::requestRedraw);

        InvalidationListener redraw = obs -> requestRedraw();
        centres.addListener((javafx.collections.ListChangeListener<NvCentre>) c -> requestRedraw());
        theta.addListener(redraw);
        phi.addListener(redraw);
        zoom.addListener(redraw);
        halfExtentM.addListener(redraw);
        overlayScript.addListener(redraw);
        overlaySamples.addListener(redraw);
        constraint.addListener(redraw);
        selectedIndex.addListener(redraw);

        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.91;
            zoom.set(MathUtil.clamp(zoom.get() * factor, 0.3, 12.0));
        });
        canvas.setOnContextMenuRequested(e -> {
            if (contextMenuRequest == null) return;
            int hit = hitTest(e.getX(), e.getY());
            contextMenuRequest.fire(hit, worldAtScreen(e.getX(), e.getY(), 0), e.getScreenX(), e.getScreenY());
        });

        timer.start();
    }

    /* ── Public surface ──────────────────────────────────────────────────── */

    public ObservableList<NvCentre> centres() { return centres; }
    public DoubleProperty thetaProperty() { return theta; }
    public DoubleProperty phiProperty() { return phi; }
    public DoubleProperty zoomProperty() { return zoom; }
    public DoubleProperty halfExtentMProperty() { return halfExtentM; }
    public ObjectProperty<EigenfieldScript> overlayScriptProperty() { return overlayScript; }
    public IntegerProperty overlaySamplesProperty() { return overlaySamples; }
    public ObjectProperty<NvConstraint> constraintProperty() { return constraint; }
    public ObjectProperty<NvEditorTool> activeToolProperty() { return activeTool; }
    public IntegerProperty selectedIndexProperty() { return selectedIndex; }
    public javafx.beans.value.ObservableObjectValue<Vec3> hoverWorldPositionProperty() { return hoverWorldPosition; }

    /** Called whenever the centre list mutates (drag end, add, delete, stamp). */
    public void setOnCentresMutated(Consumer<List<NvCentre>> handler) { this.onCentresMutated = handler; }

    /** Called when the user right-clicks. {@code hitIndex} is -1 if empty canvas. */
    public void setContextMenuRequest(ContextMenuRequest handler) { this.contextMenuRequest = handler; }

    public void setPreset(double th, double ph) { theta.set(th); phi.set(ph); }
    public void resetView() { theta.set(0.6); phi.set(0.3); zoom.set(1.0); }

    public interface ContextMenuRequest {
        void fire(int hitIndex, Vec3 worldAtCursor, double screenX, double screenY);
    }

    /* ── Mouse handling ──────────────────────────────────────────────────── */

    private void onMousePressed(MouseEvent e) {
        canvas.requestFocus();
        dragX = e.getX();
        dragY = e.getY();
        orbiting = false;
        draggingIndex = -1;
        if (e.getButton() != MouseButton.PRIMARY) return;

        var tool = activeTool.get();
        int hit = hitTest(e.getX(), e.getY());

        switch (tool) {
            case SELECT -> {
                selectedIndex.set(hit);
                if (hit >= 0) {
                    draggingIndex = hit;
                } else {
                    orbiting = true;
                }
            }
            case ADD -> {
                if (hit < 0) {
                    // Project the cursor onto the constraint surface; default
                    // depth is the constraint's projected origin or the centre
                    // of the box.
                    Vec3 world = worldAtScreen(e.getX(), e.getY(), 0);
                    world = constraint.get().project(world);
                    var centre = new NvCentre(world.x(), world.y(), world.z(), NvAxis.AXIS_PLUS_Z);
                    centres.add(centre);
                    selectedIndex.set(centres.size() - 1);
                    notifyMutated();
                }
            }
            case DELETE -> {
                if (hit >= 0) {
                    centres.remove(hit);
                    selectedIndex.set(-1);
                    notifyMutated();
                }
            }
            case ORBIT -> orbiting = true;
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        double dx = e.getX() - dragX;
        double dy = e.getY() - dragY;
        dragX = e.getX();
        dragY = e.getY();

        if (orbiting) {
            theta.set(theta.get() + dx * 0.008);
            phi.set(MathUtil.clamp(phi.get() + dy * 0.008, -1.4, 1.4));
        } else if (draggingIndex >= 0 && draggingIndex < centres.size()) {
            // Translate the centre under the active constraint. Compute world
            // delta from screen delta using the camera-aligned plane at the
            // centre's current depth.
            var c = centres.get(draggingIndex);
            Vec3 newWorld = applyScreenDeltaToWorld(c, dx, dy);
            newWorld = constraint.get().project(newWorld);
            centres.set(draggingIndex,
                new NvCentre(newWorld.x(), newWorld.y(), newWorld.z(), c.axis()));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (draggingIndex >= 0) notifyMutated();
        draggingIndex = -1;
        orbiting = false;
    }

    private void onMouseMoved(MouseEvent e) {
        Vec3 w = worldAtScreen(e.getX(), e.getY(), 0);
        hoverWorldPosition.set(w);
    }

    /* ── Camera math ─────────────────────────────────────────────────────── */

    /** Pixels per unit for the current zoom and canvas size. */
    private double scalePx() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        return Math.min(w, h) * 0.38 * zoom.get();
    }

    /**
     * Approximate inverse projection: map screen Δ(x, y) at the supplied centre's
     * world position into a world delta in the screen-aligned plane (camera
     * basis vectors). Sufficient for direct manipulation under a screen-relative
     * drag — the result is then re-projected onto the active constraint surface.
     */
    private Vec3 applyScreenDeltaToWorld(NvCentre centre, double dxScreen, double dyScreen) {
        // Screen-x basis vector in world space and screen-y basis vector in world
        // space, derived from Projection.project's forward map.
        // sx = (mx·ct − my·st)·scale + cx → screenX-basis = (ct, -st, 0)
        // sy = (mx·st·sp + my·ct·sp − mz·cp)·scale + cy → screenY-basis =
        //      (st·sp, ct·sp, -cp)
        double half = halfExtentM.get();
        double worldScale = half;
        double scale = scalePx();
        double ct = Math.cos(theta.get()), st = Math.sin(theta.get());
        double cp = Math.cos(phi.get()),   sp = Math.sin(phi.get());

        // Normalised screen delta in world-units (the projection multiplies by
        // worldScale, so dividing by worldScale·scale gets us back to normalised).
        double ndx = dxScreen / (worldScale * scale) * worldScale;
        double ndy = dyScreen / (worldScale * scale) * worldScale;

        double wx = centre.xMetres() + ct * ndx + st * sp * ndy;
        double wy = centre.yMetres() + (-st) * ndx + ct * sp * ndy;
        double wz = centre.zMetres() + 0 * ndx + (-cp) * ndy;
        return new Vec3(wx, wy, wz);
    }

    /** Approximate world coordinates at the supplied screen (x, y), in the z=0 plane (lab frame). */
    private Vec3 worldAtScreen(double screenX, double screenY, double depth) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double cx = w / 2, cy = h / 2;
        double scale = scalePx();
        double half = halfExtentM.get();
        double dx = (screenX - cx) / scale;
        double dy = (screenY - cy) / scale;
        double ct = Math.cos(theta.get()), st = Math.sin(theta.get());
        double cp = Math.cos(phi.get()),   sp = Math.sin(phi.get());

        // Solve [mx, my] in plane z=depth from screen (dx, dy):
        //   dx = mx·ct − my·st
        //   dy = mx·st·sp + my·ct·sp − depth·cp
        // → dy + depth·cp = sp·(mx·st + my·ct).
        double dySp = dy + depth * cp;
        double mxSt_plus_myCt = sp == 0 ? 0 : dySp / sp;
        // From dx and mxSt_plus_myCt with 2-eq linear system:
        // [ ct  -st ] [mx]   = [dx]
        // [ st  ct  ] [my]   = [mxSt_plus_myCt]
        // det = ct² + st² = 1.
        double mx = ct * dx + st * mxSt_plus_myCt;
        double my = -st * dx + ct * mxSt_plus_myCt;
        // Convert from normalised [−1, 1] coords back to metres.
        return new Vec3(mx * half, my * half, depth * half);
    }

    /* ── Hit-testing ─────────────────────────────────────────────────────── */

    /** Returns the index of the closest NV centre within {@link #HIT_RADIUS_PX}, or -1. */
    public int hitTest(double screenX, double screenY) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double cx = w / 2, cy = h / 2;
        double scale = scalePx();
        double half = halfExtentM.get();
        double worldScale = 1.0 / Math.max(1e-30, half);
        double th = theta.get(), ph = phi.get();

        int best = -1;
        double bestDist = HIT_RADIUS_PX;
        for (int i = 0; i < centres.size(); i++) {
            var c = centres.get(i);
            var p = Projection.project(
                c.xMetres() * worldScale, c.yMetres() * worldScale, c.zMetres() * worldScale,
                th, ph, scale, cx, cy);
            double d = Math.hypot(p[0] - screenX, p[1] - screenY);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /* ── Rendering ───────────────────────────────────────────────────────── */

    private void requestRedraw() { dirty = true; }

    private void paint(GraphicsContext g, double width, double height) {
        double cx = width / 2, cy = height / 2;
        double scale = scalePx();
        double th = theta.get(), ph = phi.get();
        double half = halfExtentM.get();
        double worldScale = 1.0 / Math.max(1e-30, half);

        // Background.
        g.setFill(Color.web("#1a1d22"));
        g.fillRect(0, 0, width, height);

        // Bounding box + axes.
        drawBoundingBox(g, th, ph, scale, cx, cy);
        drawAxes(g, th, ph, scale, cx, cy);

        // Constraint surface guide.
        drawConstraint(g, th, ph, scale, cx, cy, worldScale);

        // Optional eigenfield arrow overlay.
        if (overlayScript.get() != null) {
            drawArrowOverlay(g, th, ph, scale, cx, cy, half, worldScale);
        }

        // NV centres — sorted back-to-front for proper overdraw.
        record P(int index, double[] s, double depth) {}
        var ordered = new ArrayList<P>(centres.size());
        for (int i = 0; i < centres.size(); i++) {
            var c = centres.get(i);
            var p = Projection.project(
                c.xMetres() * worldScale, c.yMetres() * worldScale, c.zMetres() * worldScale,
                th, ph, scale, cx, cy);
            ordered.add(new P(i, p, p[2]));
        }
        ordered.sort((a, b) -> Double.compare(a.depth(), b.depth()));

        int sel = selectedIndex.get();
        for (var p : ordered) {
            double r = 4 + 1.5 * ((p.depth() + 1) * 0.5);
            boolean selected = p.index() == sel;
            Color fill = selected ? Color.web("#fde725") : Color.web("#5ec962");
            Color stroke = Color.color(0, 0, 0, 0.6);
            g.setFill(fill);
            g.fillOval(p.s()[0] - r, p.s()[1] - r, 2 * r, 2 * r);
            g.setStroke(stroke);
            g.setLineWidth(1.0);
            g.strokeOval(p.s()[0] - r, p.s()[1] - r, 2 * r, 2 * r);
            if (selected) {
                g.setStroke(Color.web("#fde725"));
                g.setLineWidth(1.4);
                g.strokeOval(p.s()[0] - r - 4, p.s()[1] - r - 4, 2 * r + 8, 2 * r + 8);
            }
        }

        // Legend.
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format("%d centres   half = %.3g µm   constraint = %s",
            centres.size(), half * 1e6, constraint.get().displayName()),
            10, height - 10);
    }

    private void drawBoundingBox(GraphicsContext g, double th, double ph, double scale, double cx, double cy) {
        double[][] corners = {
            {-1, -1, -1}, {+1, -1, -1}, {+1, +1, -1}, {-1, +1, -1},
            {-1, -1, +1}, {+1, -1, +1}, {+1, +1, +1}, {-1, +1, +1}
        };
        int[][] edges = {
            {0,1}, {1,2}, {2,3}, {3,0},
            {4,5}, {5,6}, {6,7}, {7,4},
            {0,4}, {1,5}, {2,6}, {3,7}
        };
        var p = new double[8][];
        for (int i = 0; i < 8; i++) {
            p[i] = Projection.project(corners[i][0], corners[i][1], corners[i][2], th, ph, scale, cx, cy);
        }
        g.setStroke(Color.color(1, 1, 1, 0.16));
        g.setLineWidth(0.8);
        for (var e : edges) g.strokeLine(p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1]);
    }

    private void drawAxes(GraphicsContext g, double th, double ph, double scale, double cx, double cy) {
        double[][] axes = {{1.2, 0, 0}, {0, 1.2, 0}, {0, 0, 1.2}};
        String[] labels = {"x", "y", "z"};
        Color[] colours = { Color.web("#ef6c6c"), Color.web("#7cb26a"), Color.web("#6ea3d4") };
        var origin = Projection.project(0, 0, 0, th, ph, scale, cx, cy);
        for (int i = 0; i < 3; i++) {
            var p = Projection.project(axes[i][0], axes[i][1], axes[i][2], th, ph, scale, cx, cy);
            g.setStroke(colours[i]);
            g.setLineWidth(1.2);
            g.strokeLine(origin[0], origin[1], p[0], p[1]);
            g.setFill(colours[i]);
            g.setFont(Font.font("System", FontWeight.SEMI_BOLD, 11));
            g.fillText(labels[i], p[0] + 4, p[1] - 3);
        }
    }

    private void drawConstraint(GraphicsContext g, double th, double ph, double scale,
                                double cx, double cy, double worldScale) {
        var con = constraint.get();
        if (con instanceof NvConstraint.None) return;
        double half = halfExtentM.get();
        double[][] quad = switch (con) {
            case NvConstraint.PlaneZ p -> {
                double z = MathUtil.clamp(p.z0() / half, -1, 1);
                yield new double[][] { {-1, -1, z}, {+1, -1, z}, {+1, +1, z}, {-1, +1, z} };
            }
            case NvConstraint.PlaneY p -> {
                double y = MathUtil.clamp(p.y0() / half, -1, 1);
                yield new double[][] { {-1, y, -1}, {+1, y, -1}, {+1, y, +1}, {-1, y, +1} };
            }
            case NvConstraint.PlaneX p -> {
                double x = MathUtil.clamp(p.x0() / half, -1, 1);
                yield new double[][] { {x, -1, -1}, {x, +1, -1}, {x, +1, +1}, {x, -1, +1} };
            }
            case NvConstraint.LineX l -> {
                drawLine(g, th, ph, scale, cx, cy,
                    new double[]{-1, l.y0()/half, l.z0()/half},
                    new double[]{+1, l.y0()/half, l.z0()/half});
                yield null;
            }
            case NvConstraint.LineY l -> {
                drawLine(g, th, ph, scale, cx, cy,
                    new double[]{l.x0()/half, -1, l.z0()/half},
                    new double[]{l.x0()/half, +1, l.z0()/half});
                yield null;
            }
            case NvConstraint.LineZ l -> {
                drawLine(g, th, ph, scale, cx, cy,
                    new double[]{l.x0()/half, l.y0()/half, -1},
                    new double[]{l.x0()/half, l.y0()/half, +1});
                yield null;
            }
            case NvConstraint.None n -> null;
        };
        if (quad == null) return;
        var p0 = Projection.project(quad[0][0], quad[0][1], quad[0][2], th, ph, scale, cx, cy);
        var p1 = Projection.project(quad[1][0], quad[1][1], quad[1][2], th, ph, scale, cx, cy);
        var p2 = Projection.project(quad[2][0], quad[2][1], quad[2][2], th, ph, scale, cx, cy);
        var p3 = Projection.project(quad[3][0], quad[3][1], quad[3][2], th, ph, scale, cx, cy);
        g.setFill(Color.color(0.4, 0.6, 0.9, 0.13));
        g.fillPolygon(
            new double[]{p0[0], p1[0], p2[0], p3[0]},
            new double[]{p0[1], p1[1], p2[1], p3[1]}, 4);
        g.setStroke(Color.color(0.4, 0.6, 0.9, 0.45));
        g.setLineWidth(1.0);
        g.strokePolygon(
            new double[]{p0[0], p1[0], p2[0], p3[0]},
            new double[]{p0[1], p1[1], p2[1], p3[1]}, 4);
    }

    private void drawLine(GraphicsContext g, double th, double ph, double scale,
                          double cx, double cy, double[] a, double[] b) {
        var pa = Projection.project(a[0], a[1], a[2], th, ph, scale, cx, cy);
        var pb = Projection.project(b[0], b[1], b[2], th, ph, scale, cx, cy);
        g.setStroke(Color.color(0.4, 0.6, 0.9, 0.65));
        g.setLineWidth(1.5);
        g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
    }

    /**
     * Arrow-field overlay. Each lattice point evaluates the eigenfield script
     * and renders a colour-coded arrow whose length is normalised against the
     * field's 90th-percentile magnitude — this keeps weak-but-real field
     * structure visible alongside singular hotspots (dipoles, point sources)
     * that would otherwise dominate the {@code max}-based normalisation.
     * Colour ramps cool → warm as magnitude rises so the user reads the
     * field gradient at a glance.
     */
    private void drawArrowOverlay(GraphicsContext g, double th, double ph, double scale,
                                  double cx, double cy, double half, double worldScale) {
        int n = Math.max(2, overlaySamples.get());
        double step = 2.0 * half / (n - 1);
        var samples = new ArrayList<double[]>(n * n * n);
        for (int ix = 0; ix < n; ix++) {
            double x = -half + ix * step;
            for (int iy = 0; iy < n; iy++) {
                double y = -half + iy * step;
                for (int iz = 0; iz < n; iz++) {
                    double z = -half + iz * step;
                    Vec3 v;
                    try {
                        var raw = overlayScript.get().evaluate(x, y, z);
                        v = raw == null ? Vec3.ZERO : raw;
                    } catch (Throwable t) {
                        v = Vec3.ZERO;
                    }
                    double mag = v.magnitude();
                    if (!Double.isFinite(mag)) mag = 0;
                    samples.add(new double[]{x, y, z, v.x(), v.y(), v.z(), mag});
                }
            }
        }
        // Use the 90th percentile rather than the max, so a single dipole
        // singularity doesn't reduce every other arrow to a stub.
        double pct90 = percentile(samples, 6, 0.90);
        double pct10 = percentile(samples, 6, 0.10);
        if (pct90 <= 0) return;
        double arrowLen = step * 0.65;
        double vecScale = arrowLen / pct90;
        double maxArrowLen = step * 1.0;  // visual clamp for hotspots

        for (var s : samples) {
            double mag = s[6];
            if (mag < 1e-30) continue;
            double rawLen = mag * vecScale;
            double drawLen = Math.min(rawLen, maxArrowLen);
            double dirX = s[3] / mag, dirY = s[4] / mag, dirZ = s[5] / mag;
            double tailX = s[0], tailY = s[1], tailZ = s[2];
            double headX = tailX + dirX * drawLen;
            double headY = tailY + dirY * drawLen;
            double headZ = tailZ + dirZ * drawLen;
            var tail = Projection.project(tailX * worldScale, tailY * worldScale, tailZ * worldScale, th, ph, scale, cx, cy);
            var head = Projection.project(headX * worldScale, headY * worldScale, headZ * worldScale, th, ph, scale, cx, cy);
            // Colour: cool blue at the 10th percentile → warm yellow at the 90th.
            double t = pct90 > pct10 ? (mag - pct10) / (pct90 - pct10) : 0.5;
            t = MathUtil.clamp(t, 0, 1);
            Color colour = lerpColour(t);
            g.setStroke(colour);
            g.setLineWidth(1.4);
            g.strokeLine(tail[0], tail[1], head[0], head[1]);
            // Arrowhead — two short strokes back from the head.
            double angle = Math.atan2(head[1] - tail[1], head[0] - tail[0]);
            double hx = head[0], hy = head[1];
            double ah = 4.0;
            g.strokeLine(hx, hy, hx - ah * Math.cos(angle - 0.45), hy - ah * Math.sin(angle - 0.45));
            g.strokeLine(hx, hy, hx - ah * Math.cos(angle + 0.45), hy - ah * Math.sin(angle + 0.45));
        }
    }

    /** Linear lerp between cool (blue) and warm (yellow) — viridis-ish endpoints. */
    private static Color lerpColour(double t) {
        // Cool: #4d80ff (light blue-violet) → Warm: #ffd14d (warm yellow)
        double r = 0.30 + (1.00 - 0.30) * t;
        double gC = 0.50 + (0.82 - 0.50) * t;
        double b = 1.00 + (0.30 - 1.00) * t;
        return Color.color(r, gC, b, 0.85);
    }

    /** Approximate percentile of column {@code colIdx} across {@code samples}. */
    private static double percentile(List<double[]> samples, int colIdx, double p) {
        var copy = new ArrayList<Double>(samples.size());
        for (var s : samples) copy.add(s[colIdx]);
        java.util.Collections.sort(copy);
        if (copy.isEmpty()) return 0;
        int idx = (int) Math.round((copy.size() - 1) * p);
        return copy.get(idx);
    }

    private void notifyMutated() {
        if (onCentresMutated != null) onCentresMutated.accept(new ArrayList<>(centres));
    }

    public void stop() { timer.stop(); }
}
