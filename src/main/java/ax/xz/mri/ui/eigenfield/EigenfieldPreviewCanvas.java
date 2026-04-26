package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.ui.canvas.Projection;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.util.MathUtil;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone 3D vector field preview.
 *
 * <p>Samples the bound {@link EigenfieldScript} on a rectangular grid and
 * renders arrow glyphs at each sample under an orthographic projection.
 * Camera state (azimuth, elevation, zoom) is exposed as observable properties
 * so an enclosing pane can add preset buttons.
 *
 * <p>Rendering is fully 2D ({@link javafx.scene.canvas.Canvas}) — the same
 * technique as {@code SphereWorkbenchPane}. This keeps the dependency surface
 * to {@code javafx.controls + javafx.graphics} and matches the studio's
 * existing visual language.
 */
public final class EigenfieldPreviewCanvas extends StackPane {
    /** Default half-extent of the preview box in metres (so ±0.1 m ≈ 20 cm). */
    public static final double DEFAULT_HALF_EXTENT_M = 0.10;

    private final ResizableCanvas canvas = new ResizableCanvas();

    /** The script currently being sampled. Null → placeholder grid only. */
    private final ObjectProperty<EigenfieldScript> script = new SimpleObjectProperty<>();

    private final DoubleProperty theta = new SimpleDoubleProperty(0.6);
    private final DoubleProperty phi = new SimpleDoubleProperty(0.3);
    private final DoubleProperty zoom = new SimpleDoubleProperty(1.0);

    /** Samples per axis in the preview cube. 1..20. */
    private final IntegerProperty samplesPerAxis = new SimpleIntegerProperty(7);

    /** Half-extent of the preview cube in metres. */
    private final DoubleProperty halfExtentM = new SimpleDoubleProperty(DEFAULT_HALF_EXTENT_M);

    /** Color arrows by magnitude (hot-cold). */
    private final BooleanProperty colourByMagnitude = new SimpleBooleanProperty(true);

    /** Show the translucent wireframe cube bounding the sampled region. */
    private final BooleanProperty showBoundingBox = new SimpleBooleanProperty(true);

    /** Show coordinate axes through the origin. */
    private final BooleanProperty showAxes = new SimpleBooleanProperty(true);

    /** Scale factor for arrow length (1.0 = fit longest to one grid spacing). */
    private final DoubleProperty arrowLengthScale = new SimpleDoubleProperty(0.8);

    /** Cached samples of the field. Rebuilt when script / density / extent change. */
    private List<Sample> samples = new ArrayList<>();
    private double cachedMaxMagnitude;

    private double dragX, dragY;
    private boolean dirty = true;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w > 0 && h > 0) paint(canvas.getGraphicsContext2D(), w, h);
        }
    };

    public EigenfieldPreviewCanvas() {
        getChildren().add(canvas);
        canvas.setOnResized(this::requestRedraw);

        installRedrawOn(script, theta, phi, zoom,
            samplesPerAxis, halfExtentM,
            colourByMagnitude, showBoundingBox, showAxes, arrowLengthScale);

        InvalidationListener resample = obs -> resample();
        script.addListener(resample);
        samplesPerAxis.addListener(resample);
        halfExtentM.addListener(resample);

        canvas.setOnMousePressed(e -> { dragX = e.getX(); dragY = e.getY(); });
        canvas.setOnMouseDragged(e -> {
            if (e.isPrimaryButtonDown()) {
                theta.set(theta.get() + (e.getX() - dragX) * 0.008);
                phi.set(MathUtil.clamp(phi.get() + (e.getY() - dragY) * 0.008, -1.4, 1.4));
                dragX = e.getX();
                dragY = e.getY();
            }
        });
        canvas.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.91;
            zoom.set(MathUtil.clamp(zoom.get() * factor, 0.3, 6.0));
        });

        timer.start();
        resample();
    }

    // --- Public property accessors ---

    public ObjectProperty<EigenfieldScript> scriptProperty() { return script; }
    public DoubleProperty thetaProperty() { return theta; }
    public DoubleProperty phiProperty() { return phi; }
    public DoubleProperty zoomProperty() { return zoom; }
    public IntegerProperty samplesPerAxisProperty() { return samplesPerAxis; }
    public DoubleProperty halfExtentMProperty() { return halfExtentM; }
    public BooleanProperty colourByMagnitudeProperty() { return colourByMagnitude; }
    public BooleanProperty showBoundingBoxProperty() { return showBoundingBox; }
    public BooleanProperty showAxesProperty() { return showAxes; }
    public DoubleProperty arrowLengthScaleProperty() { return arrowLengthScale; }

    public void setPreset(double thetaValue, double phiValue) {
        theta.set(thetaValue);
        phi.set(phiValue);
    }

    public void resetView() {
        theta.set(0.6);
        phi.set(0.3);
        zoom.set(1.0);
    }

    /** Force a resample + redraw (e.g. after external state changes). */
    public void refresh() {
        resample();
    }

    public void stop() {
        timer.stop();
    }

    // --- Internals ---

    private void installRedrawOn(Observable... observables) {
        InvalidationListener listener = obs -> requestRedraw();
        for (var o : observables) o.addListener(listener);
    }

    private void requestRedraw() {
        dirty = true;
    }

    private void resample() {
        var current = script.get();
        int n = Math.max(2, samplesPerAxis.get());
        double half = halfExtentM.get();
        var fresh = new ArrayList<Sample>(n * n * n);

        double max = 0;
        for (int ix = 0; ix < n; ix++) {
            double px = -half + 2 * half * ix / (n - 1);
            for (int iy = 0; iy < n; iy++) {
                double py = -half + 2 * half * iy / (n - 1);
                for (int iz = 0; iz < n; iz++) {
                    double pz = -half + 2 * half * iz / (n - 1);
                    Vec3 value;
                    if (current == null) {
                        value = Vec3.ZERO;
                    } else {
                        try {
                            var v = current.evaluate(px, py, pz);
                            value = v == null ? Vec3.ZERO : sanitise(v);
                        } catch (Throwable t) {
                            value = Vec3.ZERO;
                        }
                    }
                    double mag = value.magnitude();
                    if (mag > max) max = mag;
                    fresh.add(new Sample(px, py, pz, value, mag));
                }
            }
        }
        samples = fresh;
        cachedMaxMagnitude = max == 0 ? 1 : max;
        requestRedraw();
    }

    private static Vec3 sanitise(Vec3 v) {
        double x = Double.isFinite(v.x()) ? v.x() : 0;
        double y = Double.isFinite(v.y()) ? v.y() : 0;
        double z = Double.isFinite(v.z()) ? v.z() : 0;
        return new Vec3(x, y, z);
    }

    private void paint(GraphicsContext g, double width, double height) {
        double cx = width / 2;
        double cy = height / 2;
        double scale = Math.min(width, height) * 0.38 * zoom.get();
        double th = theta.get();
        double ph = phi.get();
        double half = halfExtentM.get();
        // Normalise world coords to [-1, 1] for projection.
        double worldScale = 1.0 / Math.max(1e-12, half);

        g.setFill(Color.web("#1a1d22"));
        g.fillRect(0, 0, width, height);

        if (showBoundingBox.get()) drawBoundingBox(g, th, ph, scale, cx, cy);
        if (showAxes.get()) drawAxes(g, th, ph, scale, cx, cy);
        drawArrows(g, th, ph, scale, cx, cy, worldScale, half);
        drawLegend(g, width, height);
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
        double[][] p = new double[8][];
        for (int i = 0; i < 8; i++) {
            p[i] = Projection.project(corners[i][0], corners[i][1], corners[i][2], th, ph, scale, cx, cy);
        }
        g.setStroke(Color.color(1, 1, 1, 0.16));
        g.setLineWidth(0.8);
        for (var e : edges) {
            g.strokeLine(p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1]);
        }
    }

    private void drawAxes(GraphicsContext g, double th, double ph, double scale, double cx, double cy) {
        double[][] axes = {{1.25, 0, 0}, {0, 1.25, 0}, {0, 0, 1.25}};
        String[] labels = {"x", "y", "z"};
        Color[] colours = {
            Color.web("#ef6c6c"),
            Color.web("#7cb26a"),
            Color.web("#6ea3d4")
        };
        double[] origin = Projection.project(0, 0, 0, th, ph, scale, cx, cy);
        for (int i = 0; i < axes.length; i++) {
            var p = Projection.project(axes[i][0], axes[i][1], axes[i][2], th, ph, scale, cx, cy);
            double depth = (1 + p[2]) / 2;
            g.setStroke(colours[i]);
            g.setLineWidth(1.0 + depth);
            g.setGlobalAlpha(0.35 + 0.6 * depth);
            g.strokeLine(origin[0], origin[1], p[0], p[1]);
            g.setFill(colours[i]);
            g.setFont(Font.font("System", FontWeight.SEMI_BOLD, 11 + depth * 1.5));
            g.fillText(labels[i], p[0] + 4, p[1] - 3);
        }
        g.setGlobalAlpha(1);
    }

    private void drawArrows(GraphicsContext g, double th, double ph, double scale, double cx, double cy,
                            double worldScale, double halfExtent) {
        if (samples.isEmpty()) return;

        int n = Math.max(2, samplesPerAxis.get());
        double gridSpacing = 2 * halfExtent / (n - 1);
        double maxArrowLen = gridSpacing * arrowLengthScale.get();
        double vecScale = maxArrowLen / cachedMaxMagnitude;

        var ordered = new ArrayList<>(samples);
        // Project each and sort back-to-front for correct overdraw.
        record Projected(Sample s, double[] tail, double[] head, double depth, double magNorm) {}
        var projectedList = new ArrayList<Projected>(ordered.size());

        for (var s : ordered) {
            if (s.magnitude < 1e-18) continue;
            double tailX = s.x;
            double tailY = s.y;
            double tailZ = s.z;
            double headX = tailX + s.value.x() * vecScale;
            double headY = tailY + s.value.y() * vecScale;
            double headZ = tailZ + s.value.z() * vecScale;

            var tail = Projection.project(tailX * worldScale, tailY * worldScale, tailZ * worldScale, th, ph, scale, cx, cy);
            var head = Projection.project(headX * worldScale, headY * worldScale, headZ * worldScale, th, ph, scale, cx, cy);
            double depth = (tail[2] + head[2]) * 0.5;
            double magNorm = s.magnitude / cachedMaxMagnitude;
            projectedList.add(new Projected(s, tail, head, depth, magNorm));
        }

        projectedList.sort((a, b) -> Double.compare(a.depth(), b.depth()));

        boolean colourByMag = colourByMagnitude.get();
        for (var p : projectedList) {
            double depthAlpha = 0.45 + 0.5 * ((p.depth() + 1) * 0.5);
            Color colour = colourByMag ? magnitudeColour(p.magNorm()) : Color.web("#dce3ec");

            g.setStroke(colour);
            g.setLineWidth(1.1 + 0.9 * p.magNorm());
            g.setGlobalAlpha(Math.min(1.0, depthAlpha));
            g.strokeLine(p.tail()[0], p.tail()[1], p.head()[0], p.head()[1]);
            drawArrowHead(g, p.tail(), p.head(), colour, p.magNorm(), depthAlpha);
        }
        g.setGlobalAlpha(1);
    }

    private void drawArrowHead(GraphicsContext g, double[] tail, double[] head, Color colour,
                               double magNorm, double depthAlpha) {
        double dx = head[0] - tail[0];
        double dy = head[1] - tail[1];
        double len = Math.hypot(dx, dy);
        if (len < 2) return;
        double ux = dx / len;
        double uy = dy / len;
        double size = Math.min(9, 3 + 5 * magNorm);
        double spread = 0.55;
        double ax = head[0] - ux * size + (-uy) * size * spread;
        double ay = head[1] - uy * size + (ux) * size * spread;
        double bx = head[0] - ux * size - (-uy) * size * spread;
        double by = head[1] - uy * size - (ux) * size * spread;
        g.setFill(colour);
        g.setGlobalAlpha(Math.min(1.0, depthAlpha));
        g.fillPolygon(new double[]{head[0], ax, bx}, new double[]{head[1], ay, by}, 3);
    }

    private Color magnitudeColour(double normalised) {
        double t = MathUtil.clamp01(normalised);
        // Cool → warm gradient through teal, green, yellow, orange, red.
        double r = Math.min(1, 2 * t);
        double b = Math.max(0, 1 - 2 * t);
        double g = (t < 0.5 ? t * 2 : 1 - (t - 0.5) * 1.2);
        return Color.color(r, Math.max(0.25, g), Math.max(b, 0.15));
    }

    private void drawLegend(GraphicsContext g, double width, double height) {
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format("max |B| = %.3g   half = %.3g m   samples = %d³",
            cachedMaxMagnitude, halfExtentM.get(), samplesPerAxis.get()), 10, height - 10);
    }

    private record Sample(double x, double y, double z, Vec3 value, double magnitude) {}
}
