package ax.xz.mri.ui.viewmodel;

import module ax.xz.mri;
import module javafx.base;
import module javafx.graphics;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.ui.canvas.Camera3D;
import ax.xz.mri.ui.canvas.OrbitView3D;
import ax.xz.mri.ui.canvas.VectorFieldArrowRenderer;

/**
 * 3-D slice-plane editor viewport.
 *
 * <p>The CAD-grade companion to the 2-D heatmap pane. Built on the shared
 * {@link OrbitView3D} orbit camera, it renders the FOV bounding box, every
 * substance ({@link ContinuousMagnetisation} cloud, {@link NvEnsemble} centres
 * as dots), an optional eigenfield {@link VectorFieldArrowRenderer arrow field}
 * (whose density adapts to zoom), and the active {@link SlicePlane} as a
 * translucent quad. The user drags empty space to orbit, scrolls to zoom, and
 * drags the plane handle along its normal to translate it.
 *
 * <p>Snapping (v1):
 * <ul>
 *   <li><b>NV centres</b> — releasing a plane-drag within 50 nm of an NV
 *       centre snaps the plane to contain that centre.</li>
 *   <li><b>FOV principal axes</b> — canonical-view buttons (+X / +Y / +Z /
 *       iso) snap the camera; plane-normal preset buttons reset the plane to
 *       one of the three orthogonal slices through the origin.</li>
 * </ul>
 */
public final class Geometry3DCanvas extends OrbitView3D {

    private static final double SNAP_TO_NV_DISTANCE_M = 50e-9;

    private final ObjectProperty<CompiledSimulation> simulation = new SimpleObjectProperty<>();
    private final ObjectProperty<SlicePlane> plane = new SimpleObjectProperty<>(SlicePlane.axisY());
    private final ObjectProperty<EigenfieldScript> overlay = new SimpleObjectProperty<>();

    private final VectorFieldArrowRenderer overlayRenderer =
        new VectorFieldArrowRenderer().baseSamplesPerAxis(6).opacity(0.7);
    private final VectorFieldArrowRenderer.Field overlayField = (x, y, z) -> {
        var s = overlay.get();
        if (s == null) return Vec3.ZERO;
        var v = s.evaluate(x, y, z);
        return v == null ? Vec3.ZERO : v;
    };

    private double dragX, dragY;
    private boolean orbiting;
    private boolean draggingPlane;

    public Geometry3DCanvas() {
        super(1e-3);
        installRedrawOn(simulation, plane, overlay);
        simulation.addListener((o, a, b) -> { syncHalfExtent(); overlayRenderer.invalidate(); });
        overlay.addListener((o, a, b) -> overlayRenderer.invalidate());

        installScrollZoom();
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
    }

    /* ── Public surface ──────────────────────────────────────────────── */

    public ObjectProperty<CompiledSimulation> simulationProperty() { return simulation; }
    public ObjectProperty<SlicePlane> planeProperty() { return plane; }
    public ObjectProperty<EigenfieldScript> overlayProperty() { return overlay; }

    public void setIsoView()  { setPreset(0.6, 0.3); }
    public void setPlusXView() { setPreset(-Math.PI / 2, 0); }
    public void setPlusYView() { setPreset(0, 0); }
    public void setPlusZView() { setPreset(0, Math.PI / 2); }

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

    /* ── Mouse handling ──────────────────────────────────────────────── */

    private void onMousePressed(MouseEvent e) {
        canvas.requestFocus();
        dragX = e.getX();
        dragY = e.getY();
        orbiting = false;
        draggingPlane = false;
        if (e.getButton() != MouseButton.PRIMARY) return;
        // Plane drag is initiated by clicking near the plane's centre handle
        // (projection of plane.origin); other clicks orbit the camera.
        double[] originScreen = camera().projectMetres(plane.get().origin());
        double d = Math.hypot(e.getX() - originScreen[0], e.getY() - originScreen[1]);
        if (d < 14) draggingPlane = true; else orbiting = true;
    }

    private void onMouseDragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        double dx = e.getX() - dragX;
        double dy = e.getY() - dragY;
        dragX = e.getX();
        dragY = e.getY();
        if (orbiting) {
            orbitBy(dx, dy);
        } else if (draggingPlane) {
            // Translate the plane along its normal. Map screen-y "into the
            // screen" so dragging down moves the plane in +normal.
            var cam = camera();
            double delta = -dy / cam.scale() * cam.halfExtentM();
            plane.set(plane.get().withOffsetAlongNormal(delta));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (draggingPlane) {
            var sim = simulation.get();
            if (sim != null) {
                Vec3 snapped = null;
                double bestDist = SNAP_TO_NV_DISTANCE_M;
                for (var s : sim.substances()) {
                    if (s instanceof NvEnsemble nv) {
                        for (var c : nv.centres()) {
                            Vec3 pos = new Vec3(c.xMetres(), c.yMetres(), c.zMetres());
                            double d = Math.abs(plane.get().signedDistance(pos));
                            if (d < bestDist) { bestDist = d; snapped = pos; }
                        }
                    }
                }
                if (snapped != null) {
                    var p = plane.get();
                    plane.set(p.withOrigin(p.origin().plus(p.normal().scale(p.signedDistance(snapped)))));
                }
            }
        }
        orbiting = false;
        draggingPlane = false;
    }

    /* ── FOV extent ──────────────────────────────────────────────────── */

    /**
     * Bounding half-extent across all substances in the active simulation,
     * with a 1 mm fallback when no substances are bound. The camera, FOV box,
     * axes, slice-plane quad, and eigenfield overlay all size against this, so
     * the simulation has one source of truth.
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
        return new Vec3(hx > 0 ? hx : 1e-3, hy > 0 ? hy : 1e-3, hz > 0 ? hz : 1e-3);
    }

    private double halfExtentForScale() {
        var h = fovHalfExtent();
        return Math.max(Math.max(h.x(), h.y()), h.z());
    }

    /** Keep the camera's normalisation scale in step with the FOV. */
    private void syncHalfExtent() { halfExtentMProperty().set(halfExtentForScale()); }

    /* ── Rendering ───────────────────────────────────────────────────── */

    @Override
    protected void drawScene(GraphicsContext g, Camera3D cam) {
        var sim = simulation.get();
        if (sim == null) {
            g.setFill(Color.color(1, 1, 1, 0.5));
            g.setFont(Font.font("System", 12));
            g.fillText("No simulation", 12, 18);
            return;
        }
        var fov = fovHalfExtent();
        drawFovBox(g, cam, fov);
        drawAxes(g, cam);
        drawSlicePlane(g, cam, fov);
        drawSubstances(g, cam, sim);
        if (overlay.get() != null) overlayRenderer.draw(g, cam, overlayField);
        drawLegend(g, fov);
    }

    private void drawFovBox(GraphicsContext g, Camera3D cam, Vec3 fov) {
        double hx = fov.x(), hy = fov.y(), hz = fov.z();
        double[][] corners = {
            {-hx, -hy, -hz}, {+hx, -hy, -hz}, {+hx, +hy, -hz}, {-hx, +hy, -hz},
            {-hx, -hy, +hz}, {+hx, -hy, +hz}, {+hx, +hy, +hz}, {-hx, +hy, +hz}
        };
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        var screen = new double[8][];
        for (int i = 0; i < 8; i++) screen[i] = cam.projectMetres(corners[i][0], corners[i][1], corners[i][2]);
        g.setStroke(Color.color(1, 1, 1, 0.18));
        g.setLineWidth(0.8);
        for (var e : edges) g.strokeLine(screen[e[0]][0], screen[e[0]][1], screen[e[1]][0], screen[e[1]][1]);
    }

    private void drawSlicePlane(GraphicsContext g, Camera3D cam, Vec3 fov) {
        var p = plane.get();
        double hu = Math.abs(p.u().x()) * fov.x() + Math.abs(p.u().y()) * fov.y() + Math.abs(p.u().z()) * fov.z();
        double hv = Math.abs(p.v().x()) * fov.x() + Math.abs(p.v().y()) * fov.y() + Math.abs(p.v().z()) * fov.z();
        var s0 = cam.projectMetres(p.sampleAt(-hu, -hv));
        var s1 = cam.projectMetres(p.sampleAt(+hu, -hv));
        var s2 = cam.projectMetres(p.sampleAt(+hu, +hv));
        var s3 = cam.projectMetres(p.sampleAt(-hu, +hv));
        double[] xs = {s0[0], s1[0], s2[0], s3[0]};
        double[] ys = {s0[1], s1[1], s2[1], s3[1]};
        g.setFill(Color.color(0.46, 0.78, 0.55, 0.18));
        g.fillPolygon(xs, ys, 4);
        g.setStroke(Color.color(0.46, 0.78, 0.55, 0.7));
        g.setLineWidth(1.2);
        g.strokePolygon(xs, ys, 4);

        // Plane origin handle + normal arrow.
        var hs = cam.projectMetres(p.origin());
        g.setFill(Color.web("#fde725"));
        g.fillOval(hs[0] - 4, hs[1] - 4, 8, 8);
        g.setStroke(Color.color(0, 0, 0, 0.65));
        g.setLineWidth(1);
        g.strokeOval(hs[0] - 4, hs[1] - 4, 8, 8);
        double arrowLen = Math.max(Math.max(fov.x(), fov.y()), fov.z()) * 0.25;
        var hn = cam.projectMetres(p.origin().plus(p.normal().scale(arrowLen)));
        g.setStroke(Color.web("#fde725"));
        g.setLineWidth(1.2);
        g.strokeLine(hs[0], hs[1], hn[0], hn[1]);
    }

    private void drawSubstances(GraphicsContext g, Camera3D cam, CompiledSimulation sim) {
        for (var s : sim.substances()) {
            switch (s) {
                case ContinuousMagnetisation cm -> drawContinuousMagnetisationCloud(g, cam, cm);
                case NvEnsemble nv              -> drawNvCentres(g, cam, nv);
            }
        }
    }

    private void drawContinuousMagnetisationCloud(GraphicsContext g, Camera3D cam, ContinuousMagnetisation cm) {
        double hx = cm.halfExtentXMetres(), hy = cm.halfExtentYMetres(), hz = cm.halfExtentZMetres();
        int n = 4;
        g.setFill(Color.color(0.6, 0.7, 0.9, 0.22));
        for (int ix = 0; ix < n; ix++) {
            double x = -hx + 2 * hx * ix / (n - 1);
            for (int iy = 0; iy < n; iy++) {
                double y = -hy + 2 * hy * iy / (n - 1);
                for (int iz = 0; iz < n; iz++) {
                    double z = -hz + 2 * hz * iz / (n - 1);
                    var p = cam.projectMetres(x, y, z);
                    g.fillOval(p[0] - 1.5, p[1] - 1.5, 3, 3);
                }
            }
        }
    }

    private void drawNvCentres(GraphicsContext g, Camera3D cam, NvEnsemble nv) {
        g.setFill(Color.web("#5ec962"));
        g.setStroke(Color.color(0, 0, 0, 0.65));
        g.setLineWidth(0.7);
        for (var c : nv.centres()) {
            var p = cam.projectMetres(c.xMetres(), c.yMetres(), c.zMetres());
            g.fillOval(p[0] - 3, p[1] - 3, 6, 6);
            g.strokeOval(p[0] - 3, p[1] - 3, 6, 6);
        }
    }

    private void drawLegend(GraphicsContext g, Vec3 fov) {
        var p = plane.get();
        double maxHalf = Math.max(Math.max(fov.x(), fov.y()), fov.z());
        String unit;
        double scale;
        if (maxHalf < 1e-3) { unit = "µm"; scale = 1e6; }
        else if (maxHalf < 1.0) { unit = "mm"; scale = 1e3; }
        else { unit = "m"; scale = 1.0; }
        g.setGlobalAlpha(1);
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format(
            "plane n=(%.2f, %.2f, %.2f)  origin=(%.2f, %.2f, %.2f) %s  FOV=±%.2f %s",
            p.normal().x(), p.normal().y(), p.normal().z(),
            p.origin().x() * scale, p.origin().y() * scale, p.origin().z() * scale, unit,
            maxHalf * scale, unit),
            10, canvas.getHeight() - 10);
    }
}
