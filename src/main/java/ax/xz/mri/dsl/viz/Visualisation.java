package ax.xz.mri.dsl.viz;

import java.util.List;

/**
 * Typed UI element a {@link ax.xz.mri.dsl.Script} can push into the
 * harness's output panel. The script DSL stays JavaFX-free — the renderer
 * in the procedure pane maps each kind onto a chart.
 *
 * <p>Scripts call {@link ax.xz.mri.dsl.ScriptContext#show
 * ScriptContext.show(viz)} with one of the nested records. The harness
 * keys outputs by {@link #id()} so subsequent emissions replace the named
 * panel — no double-rendering, no manual clearing.
 */
public sealed interface Visualisation {

    /** Stable identifier — re-emitting with the same id replaces the previous panel. */
    String id();

    /** Title shown above the panel. */
    String title();

    /* ── Line plot ─────────────────────────────────────────────────────── */

    /** Linear or log axis scaling for an axis on a {@link Line} plot. */
    enum AxisScale { LINEAR, LOG10 }

    /** One or more (x, y) series rendered on shared axes. */
    record Line(
        String id, String title,
        String xLabel, String yLabel,
        AxisScale yScale,
        List<Series> series
    ) implements Visualisation {

        public Line {
            yScale = yScale == null ? AxisScale.LINEAR : yScale;
            series = series == null ? List.of() : List.copyOf(series);
        }

        /** Single-series convenience. */
        public static Line of(String id, String title, double[] x, double[] y) {
            return new Line(id, title, "", "", AxisScale.LINEAR,
                List.of(new Series(title, x, y)));
        }

        /** Single-series with axis labels. */
        public static Line of(String id, String title, String xLabel, String yLabel,
                              double[] x, double[] y) {
            return new Line(id, title, xLabel, yLabel, AxisScale.LINEAR,
                List.of(new Series(title, x, y)));
        }

        /** Multi-series, linear y. */
        public Line(String id, String title, String xLabel, String yLabel, List<Series> series) {
            this(id, title, xLabel, yLabel, AxisScale.LINEAR, series);
        }

        /**
         * One labelled (x, y) series. The optional {@code sigma} array, when
         * non-null, renders a translucent {@code ±σ} band underneath the line
         * — Python's {@code fill_between(y-σ, y+σ)} pattern. {@code sigma}
         * must have the same length as {@code y}.
         */
        public record Series(String label, double[] x, double[] y, double[] sigma) {
            public Series {
                if (x == null || y == null) throw new IllegalArgumentException("Line.Series x/y must be non-null");
                if (x.length != y.length) {
                    throw new IllegalArgumentException("Line.Series x/y length mismatch: "
                        + x.length + " vs " + y.length);
                }
                if (sigma != null && sigma.length != y.length) {
                    throw new IllegalArgumentException("Line.Series sigma length must match y: "
                        + sigma.length + " vs " + y.length);
                }
                x = x.clone();
                y = y.clone();
                sigma = sigma == null ? null : sigma.clone();
            }

            /** Convenience: series with no uncertainty band. */
            public Series(String label, double[] x, double[] y) {
                this(label, x, y, null);
            }
        }
    }

    /* ── Heatmap ───────────────────────────────────────────────────────── */

    /** 2-D image. {@code data[row][col]} indexed first by y then by x. */
    record Heatmap(
        String id, String title,
        double[][] data,
        double xMin, double xMax,
        double yMin, double yMax,
        String xLabel, String yLabel
    ) implements Visualisation {

        public Heatmap {
            if (data == null) throw new IllegalArgumentException("Heatmap.data must be non-null");
        }
    }

    /* ── Histogram ─────────────────────────────────────────────────────── */

    /** Counts of {@code values} into {@code bins} equal-width buckets. */
    record Histogram(
        String id, String title,
        double[] values, int bins,
        String xLabel
    ) implements Visualisation {

        public Histogram {
            if (values == null) throw new IllegalArgumentException("Histogram.values must be non-null");
            if (bins < 1) throw new IllegalArgumentException("Histogram.bins must be ≥ 1");
            values = values.clone();
        }
    }

    /* ── Bar chart ─────────────────────────────────────────────────────── */

    /** Categorical bars. {@code categories.length == values.length}. */
    record Bars(
        String id, String title,
        String[] categories, double[] values,
        String yLabel
    ) implements Visualisation {

        public Bars {
            if (categories == null || values == null) {
                throw new IllegalArgumentException("Bars.categories / values must be non-null");
            }
            if (categories.length != values.length) {
                throw new IllegalArgumentException("Bars.categories / values length mismatch: "
                    + categories.length + " vs " + values.length);
            }
            categories = categories.clone();
            values = values.clone();
        }
    }

    /* ── Scalar readout ────────────────────────────────────────────────── */

    /** A single labelled number — e.g. "best objective = 1.42e-3". */
    record Scalar(
        String id, String title,
        double value, String unit
    ) implements Visualisation {}
}
