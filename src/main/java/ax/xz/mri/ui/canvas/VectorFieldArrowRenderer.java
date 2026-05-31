package ax.xz.mri.ui.canvas;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.util.MathUtil;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Adaptive 3-D vector-field arrow renderer shared by every {@link OrbitView3D}.
 *
 * <p>Samples a field on a cubic lattice and draws colour-coded arrow glyphs
 * under the orbit camera. The lattice density <em>tracks the camera zoom</em>:
 * the number of arrows per axis grows roughly in proportion to
 * {@link Camera3D#zoom()} so the on-screen arrow spacing stays about constant.
 * Zoom in and finer field structure fills in; zoom out and the field
 * declutters — you can always read what the field is doing on screen.
 *
 * <p>Magnitudes are normalised against the field's 90th percentile, so a single
 * singularity (a dipole apex, a point source) doesn't shrink every other arrow
 * to a stub. Arrows are depth-sorted for correct overdraw and faded with depth.
 *
 * <p>The sampled lattice is cached and only rebuilt when the effective density,
 * the bounding half-extent, or the field definition ({@link #invalidate()})
 * change — so orbiting the camera never resamples the (potentially expensive)
 * field.
 */
public final class VectorFieldArrowRenderer {

    /** A field to visualise: lab-frame metres → field vector (any units). */
    @FunctionalInterface
    public interface Field {
        Vec3 at(double xMetres, double yMetres, double zMetres);
    }

    /** Render summary a caller can surface in a legend. */
    public record Result(double maxMagnitude, double percentile90, int samplesPerAxis) {
        public static final Result EMPTY = new Result(0, 0, 0);
    }

    /** Lattice density at zoom = 1; the effective count scales up/down with zoom. */
    private int baseSamplesPerAxis = 7;
    /**
     * Cap on the adaptive density. Kept modest: a 3-D lattice projects all its
     * depth layers onto the 2-D canvas, so very high counts read as a cluttered
     * wall rather than a field. The linear zoom scaling reaches this cap around
     * zoom ×2 — enough to fill the gaps a magnified view would otherwise show
     * while staying legible.
     */
    private int maxSamplesPerAxis = 13;
    /** Arrow length as a fraction of the lattice spacing at the 90th-percentile magnitude. */
    private double arrowLengthScale = 0.6;
    /** Colour arrows by magnitude (else a flat cool tone). */
    private boolean colourByMagnitude = true;
    /** Overall opacity — overlays sit translucently behind foreground glyphs. */
    private double opacity = 1.0;
    /** Base stroke width. */
    private double lineWidth = 1.3;

    // Sample cache — packed [x, y, z, vx, vy, vz, mag] per lattice point.
    private double[] cache;
    private int cacheCount;
    private int cacheN = -1;
    private double cacheHalf = Double.NaN;
    private int epoch;
    private int cacheEpoch = -1;

    public VectorFieldArrowRenderer baseSamplesPerAxis(int n) {
        int v = Math.max(2, n);
        if (v != baseSamplesPerAxis) { baseSamplesPerAxis = v; invalidate(); }
        return this;
    }
    public VectorFieldArrowRenderer maxSamplesPerAxis(int n) { this.maxSamplesPerAxis = Math.max(3, n); return this; }
    public VectorFieldArrowRenderer arrowLengthScale(double s) { this.arrowLengthScale = s; return this; }
    public VectorFieldArrowRenderer colourByMagnitude(boolean b) { this.colourByMagnitude = b; return this; }
    public VectorFieldArrowRenderer opacity(double a) { this.opacity = a; return this; }
    public VectorFieldArrowRenderer lineWidth(double w) { this.lineWidth = w; return this; }

    public int baseSamplesPerAxis() { return baseSamplesPerAxis; }

    /** Force a resample on the next {@link #draw} — call when the field definition changes. */
    public void invalidate() { epoch++; }

    /** Effective lattice density for {@code zoom} — scales so screen spacing stays ≈ constant. */
    public int effectiveSamplesPerAxis(double zoom) {
        int n = (int) Math.round(1 + (baseSamplesPerAxis - 1) * Math.max(zoom, 0.0));
        return Math.max(3, Math.min(maxSamplesPerAxis, n));
    }

    /**
     * Sample (if stale) and draw the field's arrows under {@code cam}. Returns a
     * {@link Result} describing what was drawn (for legends).
     */
    public Result draw(GraphicsContext g, Camera3D cam, Field field) {
        int n = effectiveSamplesPerAxis(cam.zoom());
        double half = cam.halfExtentM();
        resampleIfStale(field, n, half);
        if (cacheCount == 0) return Result.EMPTY;

        double[] sorted = new double[cacheCount];
        for (int i = 0; i < cacheCount; i++) sorted[i] = cache[i * 7 + 6];
        Arrays.sort(sorted);
        double max = sorted[cacheCount - 1];
        double pct90 = percentile(sorted, 0.90);
        double pct10 = percentile(sorted, 0.10);
        if (!(pct90 > 0)) return new Result(max, pct90, n);

        double spacing = n <= 1 ? 2 * half : 2 * half / (n - 1);
        double vecScale = (spacing * arrowLengthScale) / pct90;
        double maxArrowLen = spacing * 0.85;          // clamp hotspots short of the next cell
        double width = cam.cx() * 2, height = cam.cy() * 2, margin = 48;

        record Glyph(double[] tail, double[] head, double depth, double t) {}
        var glyphs = new ArrayList<Glyph>(cacheCount);
        for (int i = 0; i < cacheCount; i++) {
            int o = i * 7;
            double mag = cache[o + 6];
            if (mag < 1e-30) continue;
            double[] tail = cam.projectMetres(cache[o], cache[o + 1], cache[o + 2]);
            if (tail[0] < -margin || tail[0] > width + margin
                || tail[1] < -margin || tail[1] > height + margin) continue;   // off-screen cull
            double drawLen = Math.min(mag * vecScale, maxArrowLen);
            double k = drawLen / mag;
            double[] head = cam.projectMetres(
                cache[o] + cache[o + 3] * k, cache[o + 1] + cache[o + 4] * k, cache[o + 2] + cache[o + 5] * k);
            double t = pct90 > pct10 ? MathUtil.clamp((mag - pct10) / (pct90 - pct10), 0, 1) : 0.5;
            glyphs.add(new Glyph(tail, head, (tail[2] + head[2]) * 0.5, t));
        }
        glyphs.sort((a, b) -> Double.compare(a.depth(), b.depth()));

        for (var gl : glyphs) {
            double depthAlpha = 0.45 + 0.5 * ((gl.depth() + 1) * 0.5);
            double a = MathUtil.clamp(Math.min(1.0, depthAlpha) * opacity, 0, 1);
            Color colour = colourByMagnitude ? ramp(gl.t()) : Color.web("#9fb4cc");
            g.setGlobalAlpha(a);
            g.setStroke(colour);
            g.setLineWidth(lineWidth + 0.8 * gl.t());
            g.strokeLine(gl.tail()[0], gl.tail()[1], gl.head()[0], gl.head()[1]);
            drawHead(g, gl.tail(), gl.head(), colour, gl.t());
        }
        g.setGlobalAlpha(1);
        return new Result(max, pct90, n);
    }

    private void resampleIfStale(Field field, int n, double half) {
        if (cache != null && n == cacheN && half == cacheHalf && epoch == cacheEpoch) return;
        int count = n * n * n;
        double[] data = new double[count * 7];
        int k = 0;
        for (int ix = 0; ix < n; ix++) {
            double x = n == 1 ? 0 : -half + 2 * half * ix / (n - 1);
            for (int iy = 0; iy < n; iy++) {
                double y = n == 1 ? 0 : -half + 2 * half * iy / (n - 1);
                for (int iz = 0; iz < n; iz++) {
                    double z = n == 1 ? 0 : -half + 2 * half * iz / (n - 1);
                    double vx = 0, vy = 0, vz = 0;
                    try {
                        Vec3 v = field.at(x, y, z);
                        if (v != null) { vx = finite(v.x()); vy = finite(v.y()); vz = finite(v.z()); }
                    } catch (Throwable ignored) { /* contributes a zero arrow */ }
                    data[k] = x; data[k + 1] = y; data[k + 2] = z;
                    data[k + 3] = vx; data[k + 4] = vy; data[k + 5] = vz;
                    data[k + 6] = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    k += 7;
                }
            }
        }
        cache = data; cacheCount = count; cacheN = n; cacheHalf = half; cacheEpoch = epoch;
    }

    private void drawHead(GraphicsContext g, double[] tail, double[] head, Color colour, double t) {
        double dx = head[0] - tail[0], dy = head[1] - tail[1];
        double len = Math.hypot(dx, dy);
        if (len < 2) return;
        double ux = dx / len, uy = dy / len;
        double size = Math.min(9, 3 + 5 * t);
        double spread = 0.55;
        double ax = head[0] - ux * size + (-uy) * size * spread;
        double ay = head[1] - uy * size + (ux) * size * spread;
        double bx = head[0] - ux * size - (-uy) * size * spread;
        double by = head[1] - uy * size - (ux) * size * spread;
        g.setFill(colour);
        g.fillPolygon(new double[]{head[0], ax, bx}, new double[]{head[1], ay, by}, 3);
    }

    /** Cool → warm ramp (blue → yellow → red) — reads field magnitude at a glance. */
    private static Color ramp(double t) {
        t = MathUtil.clamp(t, 0, 1);
        double r, g, b;
        if (t < 0.5) {
            double u = t * 2;                          // blue → yellow
            r = 0.23 + (0.96 - 0.23) * u;
            g = 0.51 + (0.80 - 0.51) * u;
            b = 0.96 + (0.20 - 0.96) * u;
        } else {
            double u = (t - 0.5) * 2;                  // yellow → red
            r = 0.96 + (0.94 - 0.96) * u;
            g = 0.80 + (0.27 - 0.80) * u;
            b = 0.20 + (0.27 - 0.20) * u;
        }
        return Color.color(r, g, b);
    }

    private static double percentile(double[] sortedAscending, double p) {
        if (sortedAscending.length == 0) return 0;
        int idx = (int) Math.round((sortedAscending.length - 1) * p);
        return sortedAscending[idx];
    }

    private static double finite(double v) { return Double.isFinite(v) ? v : 0; }
}
