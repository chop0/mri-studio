package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.canvas.Camera3D;
import ax.xz.mri.ui.canvas.OrbitView3D;
import ax.xz.mri.ui.canvas.VectorFieldArrowRenderer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Standalone 3-D vector-field preview (the "B-field viewer").
 *
 * <p>Renders the bound {@link EigenfieldScript} as a {@link VectorFieldArrowRenderer
 * vector-arrow field} under the shared orbit camera ({@link OrbitView3D}). The
 * arrow lattice density adapts to zoom — zoom in and more field arrows fill in
 * so the field stays legible at any scale.
 *
 * <p>On top of the shared 3-D scaffolding this adds an auto-fit heuristic that
 * frames a script's dominant spatial feature ({@link #autoDetectHalfExtent}).
 */
public final class EigenfieldPreviewCanvas extends OrbitView3D {
    /** Default half-extent of the preview box in metres (so ±0.1 m ≈ 20 cm). */
    public static final double DEFAULT_HALF_EXTENT_M = 0.10;

    /** The script currently being sampled. Null → empty cube only. */
    private final ObjectProperty<EigenfieldScript> script = new SimpleObjectProperty<>();

    /** Lattice density at zoom = 1 (the renderer scales it with zoom). 2..21. */
    private final IntegerProperty samplesPerAxis = new SimpleIntegerProperty(7);
    /** Colour arrows by magnitude (hot-cold). */
    private final BooleanProperty colourByMagnitude = new SimpleBooleanProperty(true);
    /** Show the translucent wireframe cube bounding the sampled region. */
    private final BooleanProperty showBoundingBox = new SimpleBooleanProperty(true);
    /** Show coordinate axes through the origin. */
    private final BooleanProperty showAxes = new SimpleBooleanProperty(true);
    /** Scale factor for arrow length (1.0 = fit longest to one grid spacing). */
    private final DoubleProperty arrowLengthScale = new SimpleDoubleProperty(0.6);

    private final VectorFieldArrowRenderer renderer = new VectorFieldArrowRenderer();
    private final VectorFieldArrowRenderer.Field field = (x, y, z) -> {
        var s = script.get();
        if (s == null) return Vec3.ZERO;
        var v = s.evaluate(x, y, z);
        return v == null ? Vec3.ZERO : v;
    };
    private VectorFieldArrowRenderer.Result lastResult = VectorFieldArrowRenderer.Result.EMPTY;

    public EigenfieldPreviewCanvas() {
        super(DEFAULT_HALF_EXTENT_M);
        renderer.baseSamplesPerAxis(samplesPerAxis.get())
                .arrowLengthScale(arrowLengthScale.get())
                .colourByMagnitude(colourByMagnitude.get());

        installOrbitControls();
        installRedrawOn(script, samplesPerAxis, colourByMagnitude, showBoundingBox, showAxes, arrowLengthScale);

        script.addListener((o, a, b) -> renderer.invalidate());
        samplesPerAxis.addListener((o, a, b) -> renderer.baseSamplesPerAxis(b.intValue()));
        arrowLengthScale.addListener((o, a, b) -> renderer.arrowLengthScale(b.doubleValue()));
        colourByMagnitude.addListener((o, a, b) -> renderer.colourByMagnitude(b));
    }

    // --- Public property accessors ---

    public ObjectProperty<EigenfieldScript> scriptProperty() { return script; }
    public IntegerProperty samplesPerAxisProperty() { return samplesPerAxis; }
    public BooleanProperty colourByMagnitudeProperty() { return colourByMagnitude; }
    public BooleanProperty showBoundingBoxProperty() { return showBoundingBox; }
    public BooleanProperty showAxesProperty() { return showAxes; }
    public DoubleProperty arrowLengthScaleProperty() { return arrowLengthScale; }

    /** Force a resample + redraw (e.g. after external state changes). */
    public void refresh() {
        renderer.invalidate();
        requestRedraw();
    }

    // --- Rendering ---

    @Override
    protected void drawScene(GraphicsContext g, Camera3D cam) {
        if (showBoundingBox.get()) drawBoundingCube(g, cam);
        if (showAxes.get()) drawAxes(g, cam);
        lastResult = renderer.draw(g, cam, field);
        drawLegend(g);
    }

    private void drawLegend(GraphicsContext g) {
        g.setGlobalAlpha(1);
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format("max |B| = %.3g   half = %.3g m   samples = %d³",
            lastResult.maxMagnitude(), halfExtentMProperty().get(), lastResult.samplesPerAxis()),
            10, canvas.getHeight() - 10);
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

    private static Vec3 sanitise(Vec3 v) {
        double x = Double.isFinite(v.x()) ? v.x() : 0;
        double y = Double.isFinite(v.y()) ? v.y() : 0;
        double z = Double.isFinite(v.z()) ? v.z() : 0;
        return new Vec3(x, y, z);
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /**
     * Auto-detect a sensible half-extent for the currently-bound script and
     * apply it to {@link #halfExtentMProperty()}. No-op when no script is
     * bound.
     */
    public void autoFitHalfExtent() {
        var s = script.get();
        if (s != null) halfExtentMProperty().set(autoDetectHalfExtent(s));
    }
}
