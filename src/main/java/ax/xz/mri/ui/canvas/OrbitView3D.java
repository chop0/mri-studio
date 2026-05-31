package ax.xz.mri.ui.canvas;

import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.util.MathUtil;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Base class for the studio's 3-D orbit-camera canvases.
 *
 * <p>Owns the {@link ResizableCanvas}, the orbit-camera state (azimuth θ,
 * elevation φ, zoom) and bounding {@link #halfExtentMProperty() half-extent},
 * a dirty-flag redraw loop, scroll-to-zoom, and the common chrome (dimmed
 * background, wireframe bounding cube, coordinate axes). Subclasses implement
 * {@link #drawScene} and pick their interaction model: a simple viewer calls
 * {@link #installOrbitControls()}; a richer editor wires its own mouse handlers
 * and calls {@link #orbitBy}/{@link #zoomBy}/{@link #installScrollZoom()}.
 *
 * <p>Every 3-D surface in the studio — the eigenfield (B-field) preview, the NV
 * editor, the slice-plane editor — extends this so they share one camera model,
 * one orthographic projection ({@link Camera3D}), and one visual language.
 * Vector fields render through {@link VectorFieldArrowRenderer}, whose lattice
 * density adapts to zoom.
 */
public abstract class OrbitView3D extends StackPane {

    protected final ResizableCanvas canvas = new ResizableCanvas();

    private final DoubleProperty theta = new SimpleDoubleProperty(0.6);
    private final DoubleProperty phi   = new SimpleDoubleProperty(0.3);
    private final DoubleProperty zoom  = new SimpleDoubleProperty(1.0);
    private final DoubleProperty halfExtentM;

    /** Zoom clamp bounds. */
    protected double zoomMin = 0.3, zoomMax = 12.0;
    /** Azimuth/elevation {@link #resetView()} returns to. */
    protected double homeTheta = 0.6, homePhi = 0.3;

    private boolean dirty = true;
    private double orbitAnchorX, orbitAnchorY;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w > 0 && h > 0) drawFrame(canvas.getGraphicsContext2D(), w, h);
        }
    };

    protected OrbitView3D(double defaultHalfExtentM) {
        this.halfExtentM = new SimpleDoubleProperty(defaultHalfExtentM);
        getChildren().add(canvas);
        canvas.setOnResized(this::requestRedraw);
        installRedrawOn(theta, phi, zoom, halfExtentM);
        timer.start();
    }

    /* ── Camera ─────────────────────────────────────────────────────────── */

    /** Immutable camera snapshot for the current frame. */
    protected Camera3D camera() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double scale = Math.min(w, h) * 0.38 * zoom.get();
        return new Camera3D(theta.get(), phi.get(), zoom.get(), scale, w / 2, h / 2, halfExtentM.get());
    }

    protected void orbitBy(double dxScreen, double dyScreen) {
        theta.set(theta.get() + dxScreen * 0.008);
        phi.set(MathUtil.clamp(phi.get() + dyScreen * 0.008, -1.4, 1.4));
    }

    protected void zoomBy(double scrollDeltaY) {
        double factor = scrollDeltaY > 0 ? 1.1 : 0.91;
        zoom.set(MathUtil.clamp(zoom.get() * factor, zoomMin, zoomMax));
    }

    /** Wire primary-drag → orbit and scroll → zoom — the simple-viewer interaction. */
    protected void installOrbitControls() {
        installScrollZoom();
        canvas.setOnMousePressed(e -> { orbitAnchorX = e.getX(); orbitAnchorY = e.getY(); });
        canvas.setOnMouseDragged(e -> {
            if (e.isPrimaryButtonDown()) {
                orbitBy(e.getX() - orbitAnchorX, e.getY() - orbitAnchorY);
                orbitAnchorX = e.getX();
                orbitAnchorY = e.getY();
            }
        });
    }

    /** Wire only scroll → zoom (for editors that own their mouse-drag handling). */
    protected void installScrollZoom() { canvas.setOnScroll(this::onScroll); }

    private void onScroll(ScrollEvent e) { zoomBy(e.getDeltaY()); }

    /* ── Properties ─────────────────────────────────────────────────────── */

    public DoubleProperty thetaProperty() { return theta; }
    public DoubleProperty phiProperty() { return phi; }
    public DoubleProperty zoomProperty() { return zoom; }
    public DoubleProperty halfExtentMProperty() { return halfExtentM; }

    public void setPreset(double t, double p) { theta.set(t); phi.set(p); }
    public void resetView() { theta.set(homeTheta); phi.set(homePhi); zoom.set(1.0); }

    /* ── Redraw loop ────────────────────────────────────────────────────── */

    protected void requestRedraw() { dirty = true; }

    /** Repaint whenever any of {@code observables} changes. */
    protected final void installRedrawOn(Observable... observables) {
        InvalidationListener l = o -> requestRedraw();
        for (var o : observables) o.addListener(l);
    }

    private void drawFrame(GraphicsContext g, double w, double h) {
        g.setFill(Color.web("#1a1d22"));
        g.fillRect(0, 0, w, h);
        drawScene(g, camera());
    }

    /** Subclass content. The dimmed background has already been cleared. */
    protected abstract void drawScene(GraphicsContext g, Camera3D cam);

    public void stop() { timer.stop(); }

    /* ── Shared chrome ──────────────────────────────────────────────────── */

    /** Translucent wireframe cube over the normalised {@code [-1,1]³} region. */
    protected void drawBoundingCube(GraphicsContext g, Camera3D cam) {
        double[][] c = {
            {-1, -1, -1}, {+1, -1, -1}, {+1, +1, -1}, {-1, +1, -1},
            {-1, -1, +1}, {+1, -1, +1}, {+1, +1, +1}, {-1, +1, +1}
        };
        int[][] e = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        double[][] p = new double[8][];
        for (int i = 0; i < 8; i++) p[i] = cam.projectNorm(c[i][0], c[i][1], c[i][2]);
        g.setStroke(Color.color(1, 1, 1, 0.16));
        g.setLineWidth(0.8);
        for (var ed : e) g.strokeLine(p[ed[0]][0], p[ed[0]][1], p[ed[1]][0], p[ed[1]][1]);
    }

    /** Coordinate axes through the origin (normalised length 1.2), depth-faded + labelled. */
    protected void drawAxes(GraphicsContext g, Camera3D cam) {
        double[][] axes = {{1.2, 0, 0}, {0, 1.2, 0}, {0, 0, 1.2}};
        String[] labels = {"x", "y", "z"};
        Color[] cols = { Color.web("#ef6c6c"), Color.web("#7cb26a"), Color.web("#6ea3d4") };
        double[] o = cam.projectNorm(0, 0, 0);
        for (int i = 0; i < 3; i++) {
            double[] p = cam.projectNorm(axes[i][0], axes[i][1], axes[i][2]);
            double depth = (1 + p[2]) / 2;
            g.setStroke(cols[i]);
            g.setLineWidth(1.0 + depth);
            g.setGlobalAlpha(0.35 + 0.6 * depth);
            g.strokeLine(o[0], o[1], p[0], p[1]);
            g.setFill(cols[i]);
            g.setFont(Font.font("System", FontWeight.SEMI_BOLD, 11 + depth * 1.5));
            g.fillText(labels[i], p[0] + 4, p[1] - 3);
        }
        g.setGlobalAlpha(1);
    }
}
