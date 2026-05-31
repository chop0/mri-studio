package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.dsl.EigenfieldScript;
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

    /* ── Auto-fit ──────────────────────────────────────────────────────── */

    /**
     * Log-scan the script's spatial-variation profile from 1 pm to 100 m and
     * pick a half-extent that frames the script's dominant spatial feature.
     *
     * <p>The signal we follow is
     * {@code Δ(s) = |⟨|B|⟩_boundary(s) − |B|(0)|} — the absolute difference
     * between the field magnitude averaged over the 6 axis-aligned cube
     * corners at radius {@code s} and the magnitude at the origin. This
     * single metric handles every common eigenfield shape:
     *
     * <ul>
     *   <li><b>Lorentzian dipole pair</b>: origin magnitude is zero
     *       (anti-parallel dipoles cancel); Δ peaks at {@code s ≈ separation}
     *       (where corners land on the dipole apexes), drops in the far
     *       field. Picks the peak.</li>
     *   <li><b>Helmholtz B0</b>: Δ is essentially zero inside the coils
     *       (uniform field) and rises sigmoidally past {@code s ≈ R} as the
     *       z-axis corners leave the plateau. Picks the inflection.</li>
     *   <li><b>Radially symmetric Gaussian</b>: Δ = 1 − exp(−s²/L²) rises
     *       sigmoidally at {@code s ≈ L}. Picks the inflection.</li>
     *   <li><b>Uniform field</b>: Δ ≡ 0; falls through to display default.</li>
     *   <li><b>Pure linear gradient</b>: Δ ∝ s with constant
     *       log-log slope; falls through to the "field magnitude ≈ 1"
     *       fallback.</li>
     * </ul>
     *
     * <p>The heuristic picks the scale where {@code |d(log Δ)/d(log s)|} —
     * the steepness of the log-log profile — is largest, then returns
     * {@code 2.5 ×} that scale so the feature sits in the inner third of
     * the displayed cube. Profiles whose log-log slope is uniform across
     * all scales (power-law / linear fields) are detected via the variance
     * of the derivative samples; those fall back to the magnitude target
     * or the {@value #DEFAULT_HALF_EXTENT_M} m default.
     *
     * <p>Returns the default 10 cm when {@code script} is {@code null} or
     * throws/returns garbage at every sampled point.
     */
    public static double autoDetectHalfExtent(EigenfieldScript script) {
        if (script == null) return DEFAULT_HALF_EXTENT_M;
        // Log-spaced scan from 1 pm to 1 m — 12 decades over 30 steps. The
        // upper bound is the largest spatial scale a desktop eigenfield
        // viewer cares about. Some scripts (e.g. the Helmholtz starter's
        // paraxial expansion) have unphysical r² growth that dominates the
        // far field; capping the scan keeps the heuristic locked onto the
        // actual physical feature instead of chasing the runaway term out
        // past 100 m.
        final int nScales = 30;
        final double scaleMin = 1e-12;
        final double scaleMax = 1e0;
        final double ratio = Math.pow(scaleMax / scaleMin, 1.0 / (nScales - 1));
        double[] scales = new double[nScales];
        double[] diffs = new double[nScales];
        double[] cornerMax = new double[nScales];

        double originMag = magOrZero(script, 0, 0, 0);

        double s = scaleMin;
        for (int i = 0; i < nScales; i++) {
            scales[i] = s;
            double[] stats = boundaryStats(script, s);
            cornerMax[i] = stats[1];
            diffs[i] = Math.abs(stats[0] - originMag);
            s *= ratio;
        }

        // Peak of the deviation profile.
        double peakDiff = 0;
        for (int i = 0; i < nScales; i++) {
            if (diffs[i] > peakDiff) peakDiff = diffs[i];
        }
        if (peakDiff < 1e-15) {
            // Uniform / radially-invariant field: nothing to lock onto.
            return findMagnitudeReasonableScale(scales, cornerMax);
        }

        // Floor at 1% of peak so the log-log derivative isn't dominated by
        // numerical noise at scales where Δ is effectively zero.
        double floor = 0.01 * peakDiff;
        double[] logDiffs = new double[nScales];
        for (int i = 0; i < nScales; i++) {
            logDiffs[i] = Math.log(Math.max(diffs[i], floor));
        }
        double dLogS = Math.log(ratio);
        double[] slopes = new double[nScales];
        for (int i = 1; i < nScales - 1; i++) {
            slopes[i] = (logDiffs[i + 1] - logDiffs[i - 1]) / (2 * dLogS);
        }
        slopes[0] = slopes[1];
        slopes[nScales - 1] = slopes[nScales - 2];

        // Power-law / scale-free detection. A field whose Δ(s) profile is a
        // pure power law (Δ ∝ s^α) has constant log-log slope inside the
        // active region (the part where Δ has risen off the floor). We
        // restrict the variance check to that interior — both i-1 and i+1
        // must also be active — so it isn't polluted by the half-step
        // straddling the floor-to-active transition.
        double meanSlope = 0;
        int interiorCount = 0;
        for (int i = 1; i < nScales - 1; i++) {
            if (diffs[i - 1] >= floor && diffs[i] >= floor && diffs[i + 1] >= floor) {
                meanSlope += slopes[i];
                interiorCount++;
            }
        }
        if (interiorCount >= 3) {
            meanSlope /= interiorCount;
            double varSlope = 0;
            for (int i = 1; i < nScales - 1; i++) {
                if (diffs[i - 1] >= floor && diffs[i] >= floor && diffs[i + 1] >= floor) {
                    varSlope += (slopes[i] - meanSlope) * (slopes[i] - meanSlope);
                }
            }
            double stdSlope = Math.sqrt(varSlope / interiorCount);
            if (stdSlope < 0.3) {
                return findMagnitudeReasonableScale(scales, cornerMax);
            }
        }

        // Peaked vs. monotone-rising classification: scan the active region
        // (diffs[i] ≥ floor) and look for a sign change in the slope. A
        // sign change from positive to negative means Δ has a peak — that's
        // a Lorentzian-style feature, and the peak scale is the answer.
        // No sign change means Δ rises monotonically; the characteristic
        // scale is the onset of the rise (first active scale).
        int peakIdx = -1;
        int firstActiveIdx = -1;
        double prevSlope = 0;
        for (int i = 1; i < nScales - 1; i++) {
            if (diffs[i] < floor) continue;
            if (firstActiveIdx < 0) firstActiveIdx = i;
            if (prevSlope > 0.3 && slopes[i] < -0.3) {
                peakIdx = i;
                break;
            }
            prevSlope = slopes[i];
        }
        double bestScale = -1;
        if (peakIdx >= 0) {
            // Peaked profile (Lorentzian-style): half-max-range geometric
            // centre would shift slightly but the peak scale itself is the
            // most informative.
            bestScale = scales[peakIdx];
        } else if (firstActiveIdx >= 0) {
            // Monotone-rising profile (Helmholtz-style, Gaussian-style):
            // the feature starts where the field first deviates from its
            // origin value. Pick the onset of the rise.
            bestScale = scales[firstActiveIdx];
        }
        if (bestScale > 0) {
            return clamp(2.5 * bestScale, 1e-9, 1e2);
        }
        return findMagnitudeReasonableScale(scales, cornerMax);
    }

    /**
     * Fallback: pick a scale whose corner magnitude is in {@code [0.5, 5]}
     * (eigenfields are normalised to {@code O(1)} at unit drive, so this is
     * where the field has "interesting" magnitude). Returns the
     * {@value #DEFAULT_HALF_EXTENT_M} m default when no scale qualifies, or
     * when corner magnitudes are essentially constant (a uniform field
     * isn't well served by any particular scale, so use the UI default).
     */
    private static double findMagnitudeReasonableScale(double[] scales, double[] cornerMax) {
        double minMag = Double.POSITIVE_INFINITY, maxMag = 0;
        for (double m : cornerMax) {
            if (m > 0) {
                if (m < minMag) minMag = m;
                if (m > maxMag) maxMag = m;
            }
        }
        if (maxMag == 0 || (minMag > 0 && maxMag / minMag < 1.5)) {
            // Effectively constant magnitude across all scales — uniform field.
            return DEFAULT_HALF_EXTENT_M;
        }
        for (int i = 0; i < scales.length; i++) {
            if (cornerMax[i] >= 0.5 && cornerMax[i] <= 5.0) {
                return clamp(scales[i], 1e-9, 1e2);
            }
        }
        return DEFAULT_HALF_EXTENT_M;
    }

    /**
     * Boundary statistics at radius {@code s}: average and max magnitudes
     * across the 6 axis-aligned cube vertices {@code (±s,0,0), (0,±s,0),
     * (0,0,±s)}. Origin is deliberately excluded — the heuristic compares
     * boundary behaviour against the origin separately. Scripts that throw
     * at exotic coordinates contribute 0 magnitudes (treated as "no
     * information at this point").
     */
    private static double[] boundaryStats(EigenfieldScript script, double s) {
        double[][] points = {
            {+s, 0, 0}, {-s, 0, 0},
            {0, +s, 0}, {0, -s, 0},
            {0, 0, +s}, {0, 0, -s}
        };
        double sum = 0, max = 0;
        for (var p : points) {
            double m = magOrZero(script, p[0], p[1], p[2]);
            sum += m;
            if (m > max) max = m;
        }
        return new double[]{sum / points.length, max};
    }

    private static double magOrZero(EigenfieldScript script, double x, double y, double z) {
        try {
            var v = script.evaluate(x, y, z);
            if (v == null) return 0;
            double m = sanitise(v).magnitude();
            return Double.isFinite(m) ? m : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /**
     * Auto-detect a sensible half-extent for the currently-bound script and
     * apply it to {@link #halfExtentMProperty()}. No-op when no script is
     * bound. Equivalent to
     * {@code halfExtentM.set(autoDetectHalfExtent(scriptProperty().get()))}.
     */
    public void autoFitHalfExtent() {
        var s = script.get();
        if (s != null) halfExtentM.set(autoDetectHalfExtent(s));
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
