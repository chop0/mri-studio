package ax.xz.mri.ui.viewmodel;

import module ax.xz.mri;
import module javafx.base;
import module javafx.graphics;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.ui.canvas.Projection;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 3-D slice-plane editor viewport.
 *
 * <p>The CAD-grade companion to the 2-D heatmap pane. Renders the FOV
 * bounding box, every substance ({@link ContinuousMagnetisation} cloud,
 * {@link NvEnsemble} centres as dots), an optional eigenfield arrow field,
 * and the active {@link SlicePlane} as a translucent quad. The user drags
 * empty space to orbit the camera, scrolls to zoom, and drags the plane
 * along its normal to translate it.
 *
 * <p>Snapping (v1):
 * <ul>
 *   <li><b>NV centres</b> — releasing a plane-drag within 50 nm of an NV
 *       centre snaps the plane to contain that centre.</li>
 *   <li><b>FOV principal axes</b> — canonical-view buttons (+X / +Y / +Z /
 *       iso) snap the camera; plane-normal preset buttons (⊥X / ⊥Y / ⊥Z)
 *       reset the plane to one of the three orthogonal slices through
 *       the origin.</li>
 * </ul>
 *
 * <p>Constraint sketches, mate references, etc. (the CAD polish from Part 8's
 * v2) ship later; the v1 here is the functional core.
 */
public final class Geometry3DCanvas extends StackPane {

    private static final double SNAP_TO_NV_DISTANCE_M = 50e-9;

    private final ResizableCanvas canvas = new ResizableCanvas();

    private final ObjectProperty<CompiledSimulation> simulation = new SimpleObjectProperty<>();
    private final ObjectProperty<SlicePlane> plane = new SimpleObjectProperty<>(SlicePlane.axisY());
    private final ObjectProperty<EigenfieldScript> overlay = new SimpleObjectProperty<>();

    private final DoubleProperty theta = new SimpleDoubleProperty(0.6);
    private final DoubleProperty phi   = new SimpleDoubleProperty(0.3);
    private final DoubleProperty zoom  = new SimpleDoubleProperty(1.0);

    private double dragX, dragY;
    private boolean orbiting;
    private boolean draggingPlane;
    private boolean dirty = true;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w > 0 && h > 0) paint(canvas.getGraphicsContext2D(), w, h);
        }
    };

    public Geometry3DCanvas() {
        getChildren().add(canvas);
        canvas.setOnResized(this::requestRedraw);
        InvalidationListener redraw = obs -> requestRedraw();
        simulation.addListener(redraw);
        plane.addListener(redraw);
        overlay.addListener(redraw);
        theta.addListener(redraw);
        phi.addListener(redraw);
        zoom.addListener(redraw);

        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.91;
            zoom.set(MathUtil.clamp(zoom.get() * factor, 0.3, 12.0));
        });
        timer.start();
    }

    /* ── Public surface ──────────────────────────────────────────────── */

    public ObjectProperty<CompiledSimulation> simulationProperty() { return simulation; }
    public ObjectProperty<SlicePlane> planeProperty() { return plane; }
    public ObjectProperty<EigenfieldScript> overlayProperty() { return overlay; }
    public DoubleProperty thetaProperty() { return theta; }
    public DoubleProperty phiProperty() { return phi; }
    public DoubleProperty zoomProperty() { return zoom; }

    public void setIsoView()  { theta.set(0.6); phi.set(0.3); }
    public void setPlusXView() { theta.set(-Math.PI / 2); phi.set(0); }
    public void setPlusYView() { theta.set(0); phi.set(0); }
    public void setPlusZView() { theta.set(0); phi.set(Math.PI / 2); }
    public void resetView() { setIsoView(); zoom.set(1.0); }

    /** Snap the plane normal to ±X / ±Y / ±Z if within {@code degrees}. */
    public void snapPlaneNormalToAxis(double degrees) {
        var n = plane.get().normal();
        double cosT = Math.cos(Math.toRadians(degrees));
        Vec3 best = null;
        double bestDot = cosT;
        for (var a : new Vec3[] { Vec3.X, Vec3.Y, Vec3.Z, Vec3.X.scale(-1), Vec3.Y.scale(-1), Vec3.Z.scale(-1) }) {
            double d = n.dot(a);
            if (d > bestDot) { bestDot = d; best = a; }
        }
        if (best != null) plane.set(SlicePlane.of(plane.get().origin(), best));
    }

    /** Translate the plane along its normal by {@code metres}, snapping to nearby NV centres on release. */
    public void translatePlaneAlongNormal(double metres) {
        plane.set(plane.get().withOffsetAlongNormal(metres));
    }

    public void stop() { timer.stop(); }

    /* ── Mouse handling ──────────────────────────────────────────────── */

    private void onMousePressed(MouseEvent e) {
        canvas.requestFocus();
        dragX = e.getX();
        dragY = e.getY();
        orbiting = false;
        draggingPlane = false;
        if (e.getButton() != MouseButton.PRIMARY) return;
        // Plane drag is initiated by clicking near the plane's center handle
        // (projection of plane.origin); other clicks orbit the camera.
        double[] originScreen = projectWorld(plane.get().origin());
        double d = Math.hypot(e.getX() - originScreen[0], e.getY() - originScreen[1]);
        if (d < 14) {
            draggingPlane = true;
        } else {
            orbiting = true;
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
        } else if (draggingPlane) {
            // Translate the plane along its world-space normal. Map the
            // screen-y delta to "into the screen" so dragging down moves
            // the plane in +normal.
            double scale = scalePx();
            double half = halfExtentForScale();
            double delta = -dy / scale * half;
            plane.set(plane.get().withOffsetAlongNormal(delta));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (draggingPlane) {
            // Snap to NV centres on release.
            var sim = simulation.get();
            if (sim != null) {
                Vec3 snapped = null;
                double bestDist = SNAP_TO_NV_DISTANCE_M;
                for (var s : sim.substances()) {
                    if (s instanceof NvEnsemble nv) {
                        for (var c : nv.centres()) {
                            Vec3 pos = new Vec3(c.xMetres(), c.yMetres(), c.zMetres());
                            double d = Math.abs(plane.get().signedDistance(pos));
                            if (d < bestDist) {
                                bestDist = d;
                                snapped = pos;
                            }
                        }
                    }
                }
                if (snapped != null) {
                    var p = plane.get();
                    // Project snap point onto the plane's normal and set origin there.
                    plane.set(p.withOrigin(p.origin().plus(p.normal().scale(p.signedDistance(snapped)))));
                }
            }
        }
        orbiting = false;
        draggingPlane = false;
    }

    /* ── Camera math ─────────────────────────────────────────────────── */

    /**
     * Bounding half-extent across all substances in the active simulation,
     * with a 1 mm fallback when no substances are bound. Used by the camera,
     * the FOV box, the axes, the slice-plane quad, and the eigenfield
     * overlay grid — all the geometry that needs a "size of the world"
     * cue derives it here, so the simulation has one source of truth.
     */
    private Vec3 fovHalfExtent() {
        var sim = simulation.get();
        if (sim == null) return new Vec3(1e-3, 1e-3, 1e-3);
        double hx = 0, hy = 0, hz = 0;
        for (var s : sim.substances()) {
            var h = s.halfExtent();
            hx = Math.max(hx, h.x());
            hy = Math.max(hy, h.y());
            hz = Math.max(hz, h.z());
        }
        return new Vec3(
            hx > 0 ? hx : 1e-3,
            hy > 0 ? hy : 1e-3,
            hz > 0 ? hz : 1e-3);
    }

    private double halfExtentForScale() {
        var h = fovHalfExtent();
        return Math.max(Math.max(h.x(), h.y()), h.z());
    }

    private double scalePx() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        return Math.min(w, h) * 0.38 * zoom.get();
    }

    /** Project a world-space (metres) point to screen coordinates via the orbit camera. */
    private double[] projectWorld(Vec3 world) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double cx = w / 2, cy = h / 2;
        double half = halfExtentForScale();
        double worldScale = 1.0 / Math.max(1e-30, half);
        return Projection.project(world.x() * worldScale, world.y() * worldScale, world.z() * worldScale,
            theta.get(), phi.get(), scalePx(), cx, cy);
    }

    /* ── Rendering ───────────────────────────────────────────────────── */

    private void requestRedraw() { dirty = true; }

    private void paint(GraphicsContext g, double width, double height) {
        g.setFill(Color.web("#1a1d22"));
        g.fillRect(0, 0, width, height);

        var sim = simulation.get();
        if (sim == null) {
            g.setFill(Color.color(1, 1, 1, 0.5));
            g.setFont(Font.font("System", 12));
            g.fillText("No simulation", 12, 18);
            return;
        }

        var h = fovHalfExtent();
        drawFovBox(g, h);
        drawAxes(g, h);
        drawSlicePlane(g, h);
        drawSubstances(g, sim);
        if (overlay.get() != null) drawEigenfieldOverlay(g, h);
        drawLegend(g, sim, width, height);
    }

    private void drawFovBox(GraphicsContext g, Vec3 fov) {
        double hx = fov.x(), hy = fov.y(), hz = fov.z();
        double[][] corners = {
            {-hx, -hy, -hz}, {+hx, -hy, -hz}, {+hx, +hy, -hz}, {-hx, +hy, -hz},
            {-hx, -hy, +hz}, {+hx, -hy, +hz}, {+hx, +hy, +hz}, {-hx, +hy, +hz}
        };
        int[][] edges = {
            {0,1}, {1,2}, {2,3}, {3,0},
            {4,5}, {5,6}, {6,7}, {7,4},
            {0,4}, {1,5}, {2,6}, {3,7}
        };
        var screen = new double[8][];
        for (int i = 0; i < 8; i++) {
            screen[i] = projectWorld(new Vec3(corners[i][0], corners[i][1], corners[i][2]));
        }
        g.setStroke(Color.color(1, 1, 1, 0.18));
        g.setLineWidth(0.8);
        for (var e : edges) g.strokeLine(screen[e[0]][0], screen[e[0]][1], screen[e[1]][0], screen[e[1]][1]);
    }

    private void drawAxes(GraphicsContext g, Vec3 fov) {
        double r = Math.max(Math.max(fov.x(), fov.y()), fov.z()) * 1.2;
        var origin = projectWorld(Vec3.ZERO);
        var px = projectWorld(new Vec3(r, 0, 0));
        var py = projectWorld(new Vec3(0, r, 0));
        var pz = projectWorld(new Vec3(0, 0, r));
        g.setLineWidth(1.2);
        g.setStroke(Color.web("#ef6c6c")); g.strokeLine(origin[0], origin[1], px[0], px[1]);
        g.setStroke(Color.web("#7cb26a")); g.strokeLine(origin[0], origin[1], py[0], py[1]);
        g.setStroke(Color.web("#6ea3d4")); g.strokeLine(origin[0], origin[1], pz[0], pz[1]);
        g.setFont(Font.font("System", 11));
        g.setFill(Color.web("#ef6c6c")); g.fillText("x", px[0] + 4, px[1] - 2);
        g.setFill(Color.web("#7cb26a")); g.fillText("y", py[0] + 4, py[1] - 2);
        g.setFill(Color.web("#6ea3d4")); g.fillText("z", pz[0] + 4, pz[1] - 2);
    }

    private void drawSlicePlane(GraphicsContext g, Vec3 fov) {
        var p = plane.get();
        // Compute the four FOV-clipped corners of the plane quad. Pick u/v
        // extents from the plane's projection of the FOV diagonal.
        double hu = Math.abs(p.u().x()) * fov.x()
                  + Math.abs(p.u().y()) * fov.y()
                  + Math.abs(p.u().z()) * fov.z();
        double hv = Math.abs(p.v().x()) * fov.x()
                  + Math.abs(p.v().y()) * fov.y()
                  + Math.abs(p.v().z()) * fov.z();
        Vec3 c0 = p.sampleAt(-hu, -hv);
        Vec3 c1 = p.sampleAt(+hu, -hv);
        Vec3 c2 = p.sampleAt(+hu, +hv);
        Vec3 c3 = p.sampleAt(-hu, +hv);
        var s0 = projectWorld(c0);
        var s1 = projectWorld(c1);
        var s2 = projectWorld(c2);
        var s3 = projectWorld(c3);
        double[] xs = {s0[0], s1[0], s2[0], s3[0]};
        double[] ys = {s0[1], s1[1], s2[1], s3[1]};
        g.setFill(Color.color(0.46, 0.78, 0.55, 0.18));
        g.fillPolygon(xs, ys, 4);
        g.setStroke(Color.color(0.46, 0.78, 0.55, 0.7));
        g.setLineWidth(1.2);
        g.strokePolygon(xs, ys, 4);

        // Plane origin handle.
        var hs = projectWorld(p.origin());
        g.setFill(Color.web("#fde725"));
        g.fillOval(hs[0] - 4, hs[1] - 4, 8, 8);
        g.setStroke(Color.color(0, 0, 0, 0.65));
        g.setLineWidth(1);
        g.strokeOval(hs[0] - 4, hs[1] - 4, 8, 8);

        // Normal arrow.
        double arrowLen = Math.max(Math.max(fov.x(), fov.y()), fov.z()) * 0.25;
        var hn = projectWorld(p.origin().plus(p.normal().scale(arrowLen)));
        g.setStroke(Color.web("#fde725"));
        g.setLineWidth(1.2);
        g.strokeLine(hs[0], hs[1], hn[0], hn[1]);
    }

    private void drawSubstances(GraphicsContext g, CompiledSimulation sim) {
        for (var s : sim.substances()) {
            switch (s) {
                case ContinuousMagnetisation cm -> drawContinuousMagnetisationCloud(g, cm);
                case NvEnsemble nv              -> drawNvCentres(g, nv);
            }
        }
    }

    private void drawContinuousMagnetisationCloud(GraphicsContext g, ContinuousMagnetisation cm) {
        // A coarse stippled cloud at the substance's bounding-box corners +
        // face centres — gives the user a sense of "the sample fills its
        // own box" without overwhelming the slice plane visual. The real
        // magnetisation lives in the heatmap pane; this is just a presence
        // indicator.
        double hx = cm.halfExtentXMetres(), hy = cm.halfExtentYMetres(), hz = cm.halfExtentZMetres();
        int n = 4;
        g.setFill(Color.color(0.6, 0.7, 0.9, 0.22));
        for (int ix = 0; ix < n; ix++) {
            double x = -hx + 2 * hx * ix / (n - 1);
            for (int iy = 0; iy < n; iy++) {
                double y = -hy + 2 * hy * iy / (n - 1);
                for (int iz = 0; iz < n; iz++) {
                    double z = -hz + 2 * hz * iz / (n - 1);
                    var p = projectWorld(new Vec3(x, y, z));
                    g.fillOval(p[0] - 1.5, p[1] - 1.5, 3, 3);
                }
            }
        }
    }

    private void drawNvCentres(GraphicsContext g, NvEnsemble nv) {
        g.setFill(Color.web("#5ec962"));
        g.setStroke(Color.color(0, 0, 0, 0.65));
        g.setLineWidth(0.7);
        for (var c : nv.centres()) {
            var p = projectWorld(new Vec3(c.xMetres(), c.yMetres(), c.zMetres()));
            g.fillOval(p[0] - 3, p[1] - 3, 6, 6);
            g.strokeOval(p[0] - 3, p[1] - 3, 6, 6);
        }
    }

    private void drawEigenfieldOverlay(GraphicsContext g, Vec3 fov) {
        EigenfieldScript script = overlay.get();
        if (script == null) return;
        int n = 4;
        double hx = fov.x(), hy = fov.y(), hz = fov.z();
        double step = Math.max(Math.max(2 * hx / (n - 1), 2 * hy / (n - 1)), 2 * hz / (n - 1));
        double maxMag = 1e-30;
        var samples = new ArrayList<double[]>(n * n * n);
        for (int ix = 0; ix < n; ix++) {
            double x = -hx + 2 * hx * ix / (n - 1);
            for (int iy = 0; iy < n; iy++) {
                double y = -hy + 2 * hy * iy / (n - 1);
                for (int iz = 0; iz < n; iz++) {
                    double z = -hz + 2 * hz * iz / (n - 1);
                    Vec3 v;
                    try {
                        var raw = script.evaluate(x, y, z);
                        v = raw == null ? Vec3.ZERO : raw;
                    } catch (Throwable t) {
                        v = Vec3.ZERO;
                    }
                    double m = v.magnitude();
                    if (Double.isFinite(m) && m > maxMag) maxMag = m;
                    samples.add(new double[]{x, y, z, v.x(), v.y(), v.z(), m});
                }
            }
        }
        double vecScale = (step * 0.6) / maxMag;
        g.setStroke(Color.color(0.45, 0.7, 0.9, 0.55));
        g.setLineWidth(1.0);
        for (var s : samples) {
            if (s[6] < 1e-30) continue;
            var tail = projectWorld(new Vec3(s[0], s[1], s[2]));
            var head = projectWorld(new Vec3(s[0] + s[3] * vecScale, s[1] + s[4] * vecScale, s[2] + s[5] * vecScale));
            g.strokeLine(tail[0], tail[1], head[0], head[1]);
        }
    }

    private void drawLegend(GraphicsContext g, CompiledSimulation sim, double width, double height) {
        var p = plane.get();
        var fov = fovHalfExtent();
        double maxHalf = Math.max(Math.max(fov.x(), fov.y()), fov.z());
        // Pick µm / mm / m depending on FOV scale; same heuristic the
        // SequenceEditorProvider's title strip uses.
        String unit;
        double scale;
        if (maxHalf < 1e-3) { unit = "µm"; scale = 1e6; }
        else if (maxHalf < 1.0) { unit = "mm"; scale = 1e3; }
        else { unit = "m"; scale = 1.0; }
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format(
            "plane n=(%.2f, %.2f, %.2f)  origin=(%.2f, %.2f, %.2f) %s  FOV=±%.2f %s",
            p.normal().x(), p.normal().y(), p.normal().z(),
            p.origin().x() * scale, p.origin().y() * scale, p.origin().z() * scale, unit,
            maxHalf * scale, unit),
            10, height - 10);
    }

    /**
     * Compile an eigenfield script source into the overlay. Errors are
     * surfaced via the returned message; {@code null} clears the overlay.
     */
    public String setOverlayScriptSource(String source) {
        if (source == null || source.isBlank()) {
            overlay.set(null);
            return null;
        }
        try {
            overlay.set(EigenfieldEngine.compile(source));
            return null;
        } catch (Exception ex) {
            overlay.set(null);
            return ex.getMessage();
        }
    }
}
