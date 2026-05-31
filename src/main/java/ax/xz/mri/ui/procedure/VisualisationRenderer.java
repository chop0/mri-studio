package ax.xz.mri.ui.procedure;

import module ax.xz.mri;
import module javafx.controls;
import module javafx.graphics;

import javafx.application.Platform;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Maps a {@link Visualisation} value onto a JavaFX {@link Node} the procedure
 * pane's Outputs panel renders. One entry point — {@link #render(Visualisation)}
 * — returns a {@link Rendered}: the {@link Node} to mount and a
 * {@link Consumer} callback the host can re-invoke each time the procedure
 * emits a new {@link Visualisation} with the same id.
 *
 * <p><b>The host MUST reuse {@link Rendered#update}</b> instead of calling
 * {@link #render} again. Re-rendering a chart from scratch every tick is the
 * flicker the user sees as the procedure runs — the same axes, series, and
 * sigma-band Pane get torn down and rebuilt 60×/s, and the layout pass
 * between teardown and remount is visible. Inside {@code update}, only the
 * series data points, axis labels, and band geometry mutate; the chart node
 * is stable across the lifetime of the procedure run.
 *
 * <p>Every rendered node is a self-contained card with a header, a chart
 * area, and bounded preferred dimensions — so a single Outputs panel can
 * stack multiple cards in a {@code ScrollPane} and each renders at a
 * predictable size.
 */
public final class VisualisationRenderer {
    private VisualisationRenderer() {}

    /** Min height for a chart card — keeps the chrome visible. */
    private static final double CARD_MIN_HEIGHT = 260;
    /** Preferred height — what the card gets in a flow layout. */
    private static final double CARD_PREF_HEIGHT = 300;

    /**
     * Result of {@link #render}: the mounted JavaFX node and an updater the
     * host calls each time the procedure re-emits a {@link Visualisation}
     * with the same id. The updater is type-tolerant — if the new value's
     * kind doesn't match what this rendered card supports, it's silently
     * ignored (the host should re-create the card in that case).
     */
    public record Rendered(Node node, Consumer<Visualisation> update) {}

    public static Rendered render(Visualisation viz) {
        return switch (viz) {
            case Visualisation.Line line       -> renderLine(line);
            case Visualisation.Heatmap heat    -> renderHeatmap(heat);
            case Visualisation.Histogram hist  -> renderHistogram(hist);
            case Visualisation.Bars bars       -> renderBars(bars);
            case Visualisation.Scalar scalar   -> renderScalar(scalar);
        };
    }

    /* ── Line ──────────────────────────────────────────────────────────── */

    private static Rendered renderLine(Visualisation.Line initial) {
        boolean log = initial.yScale() == Visualisation.AxisScale.LOG10;

        var xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setMinorTickVisible(false);

        var yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(false);
        yAxis.setMinorTickVisible(false);
        if (log) yAxis.setTickLabelFormatter(new LogTickFormatter());

        var chart = new LineChart<Number, Number>(xAxis, yAxis);
        chart.setTitle(null);
        chart.setLegendSide(Side.BOTTOM);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(true);
        chart.setMinSize(320, CARD_MIN_HEIGHT - 30);
        chart.setPrefSize(420, CARD_PREF_HEIGHT - 30);

        // Sigma band overlay — translucent fill on a Pane stacked on top of
        // the LineChart's plot region. The band paints in plot-area coordinates
        // resolved from the axes' getDisplayPosition; chart layout listeners
        // trigger a repaint so the band tracks resizes + axis-range changes.
        var bandPane = new Pane();
        bandPane.setMouseTransparent(true);
        bandPane.setManaged(false);

        // State the updater mutates: scaling factors derived from the latest
        // data range so labels + band match. Wrapped in arrays so closures can
        // see the current values without juggling mutable fields.
        final double[] xScaleRef = {1.0};
        final double[] yScaleRef = {1.0};
        final Visualisation.Line[] currentRef = {initial};

        Runnable repaintBands = () -> paintSigmaBands(
            bandPane, chart, currentRef[0], xScaleRef[0], yScaleRef[0], log);
        chart.widthProperty().addListener((obs, o, n) -> repaintBands.run());
        chart.heightProperty().addListener((obs, o, n) -> repaintBands.run());
        xAxis.lowerBoundProperty().addListener((obs, o, n) -> repaintBands.run());
        xAxis.upperBoundProperty().addListener((obs, o, n) -> repaintBands.run());
        yAxis.lowerBoundProperty().addListener((obs, o, n) -> repaintBands.run());
        yAxis.upperBoundProperty().addListener((obs, o, n) -> repaintBands.run());

        var headerLabel = new Label(initial.title() == null ? "" : initial.title());
        headerLabel.getStyleClass().add("viz-card-title");

        var chartHolder = new StackPane(chart, bandPane);
        var card = new VBox(6, headerLabel, chartHolder);
        card.getStyleClass().add("viz-card");
        card.setMinHeight(CARD_MIN_HEIGHT);
        card.setPrefHeight(CARD_PREF_HEIGHT);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);

        // Track series by label so re-emissions mutate the existing
        // XYChart.Series in place. JavaFX's LineChart animates additions /
        // removals by default; the chart already has setAnimated(false) so
        // setAll on the data list is a quick in-place swap.
        Map<String, XYChart.Series<Number, Number>> seriesByName = new LinkedHashMap<>();

        Consumer<Visualisation> updater = v -> {
            if (!(v instanceof Visualisation.Line line)) return;
            currentRef[0] = line;

            // Recompute the SI prefix from the latest data range — sigma
            // bands count too, since a ±σ envelope can dominate the y range.
            double maxAbsX = 0, maxAbsY = 0;
            for (var s : line.series()) {
                for (double x : s.x()) if (Double.isFinite(x)) maxAbsX = Math.max(maxAbsX, Math.abs(x));
                for (int i = 0; i < s.y().length; i++) {
                    double y = s.y()[i];
                    if (Double.isFinite(y)) maxAbsY = Math.max(maxAbsY, Math.abs(y));
                    if (s.sigma() != null) {
                        double up = y + s.sigma()[i];
                        double lo = y - s.sigma()[i];
                        if (Double.isFinite(up)) maxAbsY = Math.max(maxAbsY, Math.abs(up));
                        if (Double.isFinite(lo)) maxAbsY = Math.max(maxAbsY, Math.abs(lo));
                    }
                }
            }
            var xUnit = extractUnit(line.xLabel());
            var yUnit = extractUnit(line.yLabel());
            var xPrefix = xUnit == null
                ? new ax.xz.mri.util.SiFormat.UnitChoice(1.0, null)
                : ax.xz.mri.util.SiFormat.pickPrefix(maxAbsX, xUnit);
            var yPrefix = log || yUnit == null
                ? new ax.xz.mri.util.SiFormat.UnitChoice(1.0, null)
                : ax.xz.mri.util.SiFormat.pickPrefix(maxAbsY, yUnit);
            xScaleRef[0] = xPrefix.scale();
            yScaleRef[0] = yPrefix.scale();

            xAxis.setLabel(buildAxisLabel(line.xLabel(), xPrefix.label()));
            yAxis.setLabel(buildAxisLabel(line.yLabel(), yPrefix.label()));
            chart.setLegendVisible(line.series().size() > 1);
            headerLabel.setText(line.title() == null ? "" : line.title());

            // Update or add each series.
            Set<String> wanted = new LinkedHashSet<>();
            for (var s : line.series()) {
                wanted.add(s.label());
                var newPoints = buildSeriesData(s, xScaleRef[0], yScaleRef[0], log);
                var existing = seriesByName.get(s.label());
                if (existing != null) {
                    existing.getData().setAll(newPoints);
                } else {
                    var newSeries = new XYChart.Series<Number, Number>();
                    newSeries.setName(s.label());
                    newSeries.getData().setAll(newPoints);
                    seriesByName.put(s.label(), newSeries);
                    chart.getData().add(newSeries);
                }
            }
            // Remove series no longer present.
            var iter = seriesByName.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                if (!wanted.contains(e.getKey())) {
                    chart.getData().remove(e.getValue());
                    iter.remove();
                }
            }

            // Bands need to wait until the chart has laid out the new data so
            // getDisplayPosition returns real numbers. The axis-bound
            // listeners above also fire — runLater ensures one repaint after
            // they settle.
            Platform.runLater(repaintBands);
        };

        updater.accept(initial);
        return new Rendered(card, updater);
    }

    /**
     * Maximum number of points handed to a JavaFX {@link XYChart.Series}.
     * Beyond this, the renderer subsamples uniformly. JavaFX line-chart
     * layout is O(N) per redraw, and at N ≳ 2 000 the per-frame cost
     * dominates everything else — a 10 000-iter NV adaptive run would
     * crawl in the tail without this. The visual difference between 1 500
     * and 10 000 points on a fixed-size chart is imperceptible (the
     * subsample step is below one pixel for any realistic chart width).
     */
    private static final int MAX_SERIES_POINTS = 1500;

    /** Build the {@code List<XYChart.Data>} for one series under the current SI prefix scaling. */
    private static java.util.List<XYChart.Data<Number, Number>> buildSeriesData(
            Visualisation.Line.Series s, double xScale, double yScale, boolean log) {
        int n = s.x().length;
        // Subsample uniformly when the series is larger than the chart can
        // efficiently render. We always include the last point so the
        // "live tail" of an iterating procedure stays visible — that's where
        // the user is watching convergence land.
        int stride = Math.max(1, (n + MAX_SERIES_POINTS - 1) / MAX_SERIES_POINTS);
        int capacity = (n + stride - 1) / stride + 1;
        var out = new java.util.ArrayList<XYChart.Data<Number, Number>>(capacity);
        for (int i = 0; i < n; i += stride) {
            addPoint(out, s.x()[i], s.y()[i], xScale, yScale, log);
        }
        // Force-include the last sample if stride skipped past it.
        if (n > 0 && (n - 1) % stride != 0) {
            addPoint(out, s.x()[n - 1], s.y()[n - 1], xScale, yScale, log);
        }
        return out;
    }

    private static void addPoint(java.util.List<XYChart.Data<Number, Number>> out,
                                 double x, double y, double xScale, double yScale, boolean log) {
        if (log) {
            if (!(y > 0) || !Double.isFinite(y)) return;
            y = Math.log10(y);
        } else {
            y *= yScale;
        }
        out.add(new XYChart.Data<>(x * xScale, y));
    }

    /**
     * Paint every {@code ±σ} envelope on the supplied overlay pane in the
     * chart's plot-area coordinate frame. JavaFX gives axes a
     * {@code getDisplayPosition(value)} method that maps data → pixel space;
     * the pane sits inside a {@link StackPane} with the chart, so origin
     * arithmetic walks from {@code xAxis.localToScene} back to the band
     * pane's local frame.
     */
    private static void paintSigmaBands(Pane bandPane, LineChart<Number, Number> chart,
                                        Visualisation.Line line, double xScale, double yScale,
                                        boolean log) {
        bandPane.getChildren().clear();
        if (chart.getWidth() <= 0 || chart.getHeight() <= 0) return;
        var xAxis = (NumberAxis) chart.getXAxis();
        var yAxis = (NumberAxis) chart.getYAxis();
        // Walk axis local-coords back to the band pane.
        var axisOriginInScene = xAxis.localToScene(0, 0);
        var paneOriginInScene = bandPane.localToScene(0, 0);
        if (axisOriginInScene == null || paneOriginInScene == null) return;
        double ox = axisOriginInScene.getX() - paneOriginInScene.getX();
        // y origin: top of the plot area = y-axis's local y=0 in scene.
        var yAxisTopInScene = yAxis.localToScene(0, 0);
        if (yAxisTopInScene == null) return;
        double oy = yAxisTopInScene.getY() - paneOriginInScene.getY();

        int seriesIdx = 0;
        for (var s : line.series()) {
            if (s.sigma() != null) {
                int n = s.x().length;
                int stride = Math.max(1, (n + MAX_SERIES_POINTS - 1) / MAX_SERIES_POINTS);
                var fill = bandFill(seriesIdx);
                var path = new Path();
                // Upper edge — forward.
                boolean first = true;
                for (int i = 0; i < n; i += stride) {
                    appendBandPoint(path, s, i, xScale, yScale, log, ox, oy, xAxis, yAxis, +1, first);
                    first = false;
                }
                if (n > 0 && (n - 1) % stride != 0) {
                    appendBandPoint(path, s, n - 1, xScale, yScale, log, ox, oy, xAxis, yAxis, +1, false);
                }
                // Lower edge — reverse, ending at the same x as the first upper point.
                if (n > 0 && (n - 1) % stride != 0) {
                    appendBandPoint(path, s, n - 1, xScale, yScale, log, ox, oy, xAxis, yAxis, -1, false);
                }
                int lastStrided = ((n - 1) / stride) * stride;
                for (int i = lastStrided; i >= 0; i -= stride) {
                    appendBandPoint(path, s, i, xScale, yScale, log, ox, oy, xAxis, yAxis, -1, false);
                }
                path.getElements().add(new ClosePath());
                path.setFill(fill);
                path.setStroke(null);
                bandPane.getChildren().add(path);
            }
            seriesIdx++;
        }
    }

    /**
     * Append one upper- ({@code sign = +1}) or lower- ({@code sign = -1})
     * edge vertex of a sigma band. {@code first = true} emits a
     * {@link MoveTo} (path-start); subsequent vertices emit {@link LineTo}.
     */
    private static void appendBandPoint(Path path, Visualisation.Line.Series s, int i,
                                        double xScale, double yScale, boolean log,
                                        double ox, double oy,
                                        NumberAxis xAxis, NumberAxis yAxis,
                                        int sign, boolean first) {
        double xd = s.x()[i] * xScale;
        double yBound = (s.y()[i] + sign * s.sigma()[i]) * (log ? 1.0 : yScale);
        double px = ox + xAxis.getDisplayPosition(xd);
        double py = oy + yAxis.getDisplayPosition(log ? Math.log10(yBound) : yBound);
        path.getElements().add(first ? new MoveTo(px, py) : new LineTo(px, py));
    }

    private static Color bandFill(int seriesIdx) {
        // JavaFX LineChart's default 8-colour palette. Match the line and
        // drop alpha to 0.18 so the line stays clearly above the band.
        Color[] palette = {
            Color.web("#f3622d"), Color.web("#fba71b"), Color.web("#57b757"),
            Color.web("#41a9c9"), Color.web("#4258c9"), Color.web("#9a42c8"),
            Color.web("#c84164"), Color.web("#888888")
        };
        var c = palette[seriesIdx % palette.length];
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.18);
    }

    /**
     * Extract the base unit string from an axis label like {@code "x (m)"}
     * or {@code "Bz (T)"}. Returns {@code null} if the label has no
     * parenthesised unit — in which case the renderer won't try to
     * apply an SI prefix.
     */
    static String extractUnit(String label) {
        if (label == null) return null;
        int open = label.lastIndexOf('(');
        int close = label.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return null;
        var unit = label.substring(open + 1, close).strip();
        return unit.isEmpty() ? null : unit;
    }

    /** Rebuild the axis label with the resolved prefix unit substituted in. */
    static String buildAxisLabel(String original, String prefixedUnit) {
        if (original == null || original.isBlank()) return null;
        if (prefixedUnit == null) return original;
        int open = original.lastIndexOf('(');
        int close = original.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return original;
        return original.substring(0, open + 1) + prefixedUnit + original.substring(close);
    }

    /** Tick formatter that prints 10^x rather than x for log-scale plots. */
    private static final class LogTickFormatter extends javafx.util.StringConverter<Number> {
        @Override public String toString(Number value) {
            double v = value.doubleValue();
            if (Math.abs(v - Math.round(v)) < 1e-6) {
                return "10" + superscript((int) Math.round(v));
            }
            return String.format("%.1g", Math.pow(10, v));
        }
        @Override public Number fromString(String string) { return 0; }
        private static String superscript(int n) {
            var sb = new StringBuilder();
            if (n < 0) { sb.append('⁻'); n = -n; }
            String digits = String.valueOf(n);
            for (int i = 0; i < digits.length(); i++) {
                sb.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(digits.charAt(i) - '0'));
            }
            return sb.toString();
        }
    }

    /* ── Heatmap ───────────────────────────────────────────────────────── */

    private static Rendered renderHeatmap(Visualisation.Heatmap initial) {
        // Lazily resize the image to match the data dims on each update — the
        // heatmap dimensions can change between emissions (e.g. an iterative
        // reconstructor might widen its grid).
        final int[] dims = {0, 0};
        final WritableImage[] imgRef = {null};
        final Visualisation.Heatmap[] currentRef = {initial};

        var view = new ImageView();
        view.setPreserveRatio(false);
        view.setSmooth(false);

        var holder = new StackPane(view);
        holder.setMinSize(320, CARD_MIN_HEIGHT - 60);
        holder.setPrefSize(420, CARD_PREF_HEIGHT - 60);
        view.fitWidthProperty().bind(holder.widthProperty());
        view.fitHeightProperty().bind(holder.heightProperty());
        holder.setStyle("-fx-border-color: -studio-border-subtle; -fx-border-width: 1;");

        var xAxisLabel = axisLabel(initial.xLabel());
        var scaleLabel = scaleLabel(0, 1);
        var axes = new HBox(8, xAxisLabel, scaleLabel);

        var body = new VBox(4, holder, axes);
        VBox.setVgrow(holder, Priority.ALWAYS);

        var headerLabel = new Label(initial.title() == null ? "" : initial.title());
        headerLabel.getStyleClass().add("viz-card-title");

        var card = new VBox(6, headerLabel, body);
        card.getStyleClass().add("viz-card");
        card.setMinHeight(CARD_MIN_HEIGHT);
        card.setPrefHeight(CARD_PREF_HEIGHT);
        VBox.setVgrow(body, Priority.ALWAYS);

        Consumer<Visualisation> updater = v -> {
            if (!(v instanceof Visualisation.Heatmap heat)) return;
            currentRef[0] = heat;
            headerLabel.setText(heat.title() == null ? "" : heat.title());
            xAxisLabel.setText(heat.xLabel() == null ? "" : heat.xLabel());

            int rows = heat.data().length;
            int cols = rows == 0 ? 0 : heat.data()[0].length;
            if (rows == 0 || cols == 0) {
                view.setImage(null);
                return;
            }
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double[] row : heat.data()) {
                for (double val : row) {
                    if (val < min) min = val;
                    if (val > max) max = val;
                }
            }
            if (!Double.isFinite(min) || !Double.isFinite(max) || min == max) { min = 0; max = 1; }

            if (dims[0] != cols || dims[1] != rows || imgRef[0] == null) {
                imgRef[0] = new WritableImage(cols, rows);
                dims[0] = cols;
                dims[1] = rows;
                view.setImage(imgRef[0]);
            }
            PixelWriter pw = imgRef[0].getPixelWriter();
            double range = max - min;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double t = range > 0 ? (heat.data()[r][c] - min) / range : 0;
                    pw.setColor(c, rows - 1 - r, viridis(t));
                }
            }
            scaleLabel.setText(String.format("[%.3g, %.3g]", min, max));
        };

        updater.accept(initial);
        return new Rendered(card, updater);
    }

    /* ── Histogram ─────────────────────────────────────────────────────── */

    private static Rendered renderHistogram(Visualisation.Histogram initial) {
        var inner = histogramToLine(initial);
        var inside = renderLine(inner);
        Consumer<Visualisation> updater = v -> {
            if (!(v instanceof Visualisation.Histogram hist)) return;
            inside.update().accept(histogramToLine(hist));
        };
        return new Rendered(inside.node(), updater);
    }

    private static Visualisation.Line histogramToLine(Visualisation.Histogram hist) {
        double[] xs = hist.values();
        if (xs.length == 0) {
            return new Visualisation.Line(hist.id(), hist.title(),
                hist.xLabel(), "count",
                Visualisation.AxisScale.LINEAR,
                java.util.List.of(new Visualisation.Line.Series("count", new double[0], new double[0])));
        }
        double min = xs[0], max = xs[0];
        for (double v : xs) { if (v < min) min = v; if (v > max) max = v; }
        if (min == max) { min -= 0.5; max += 0.5; }
        int n = Math.max(1, hist.bins());
        long[] counts = new long[n];
        double w = (max - min) / n;
        for (double v : xs) {
            int bi = (int) ((v - min) / w);
            if (bi == n) bi--;
            if (bi >= 0 && bi < n) counts[bi]++;
        }
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = min + (i + 0.5) * w;
            y[i] = counts[i];
        }
        return new Visualisation.Line(hist.id(), hist.title(),
            hist.xLabel(), "count",
            Visualisation.AxisScale.LINEAR,
            java.util.List.of(new Visualisation.Line.Series("count", x, y)));
    }

    /* ── Bars ──────────────────────────────────────────────────────────── */

    private static Rendered renderBars(Visualisation.Bars initial) {
        final Visualisation.Bars[] currentRef = {initial};

        var canvas = new Canvas(420, CARD_PREF_HEIGHT - 60);
        var holder = new StackPane(canvas);
        holder.setMinSize(320, CARD_MIN_HEIGHT - 60);
        holder.setPrefSize(420, CARD_PREF_HEIGHT - 60);

        Runnable redraw = () -> drawBars(canvas, currentRef[0]);
        holder.widthProperty().addListener((obs, o, nn) -> {
            canvas.setWidth(holder.getWidth());
            redraw.run();
        });
        holder.heightProperty().addListener((obs, o, nn) -> {
            canvas.setHeight(holder.getHeight());
            redraw.run();
        });

        var headerLabel = new Label(initial.title() == null ? "" : initial.title());
        headerLabel.getStyleClass().add("viz-card-title");

        var card = new VBox(6, headerLabel, holder);
        card.getStyleClass().add("viz-card");
        card.setMinHeight(CARD_MIN_HEIGHT);
        card.setPrefHeight(CARD_PREF_HEIGHT);
        VBox.setVgrow(holder, Priority.ALWAYS);

        Consumer<Visualisation> updater = v -> {
            if (!(v instanceof Visualisation.Bars bars)) return;
            currentRef[0] = bars;
            headerLabel.setText(bars.title() == null ? "" : bars.title());
            redraw.run();
        };

        updater.accept(initial);
        return new Rendered(card, updater);
    }

    private static void drawBars(Canvas canvas, Visualisation.Bars bars) {
        var g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, w, h);

        int n = bars.categories().length;
        if (n == 0) return;

        double padLeft = 40, padBottom = 22, padTop = 8, padRight = 8;
        double max = 0;
        for (double v : bars.values()) max = Math.max(max, v);
        if (max == 0) max = 1;
        double plotW = Math.max(20, w - padLeft - padRight);
        double plotH = Math.max(20, h - padTop - padBottom);

        // Axes
        g.setStroke(Color.web("#5d6f88"));
        g.setLineWidth(1);
        g.strokeLine(padLeft, padTop, padLeft, padTop + plotH);
        g.strokeLine(padLeft, padTop + plotH, padLeft + plotW, padTop + plotH);
        g.setFont(Font.font(10));
        g.setFill(Color.web("#5d6f88"));
        for (int i = 0; i <= 4; i++) {
            double t = i / 4.0;
            double yv = max * t;
            double py = padTop + plotH - t * plotH;
            g.strokeLine(padLeft - 3, py, padLeft, py);
            g.fillText(String.format("%.2g", yv), 2, py + 4);
        }

        // Bars
        double barW = plotW / n;
        g.setFill(Color.web("#0f5fa6"));
        g.setFont(Font.font(10));
        for (int i = 0; i < n; i++) {
            double bh = (bars.values()[i] / max) * plotH;
            double bx = padLeft + i * barW + 2;
            double by = padTop + plotH - bh;
            g.fillRect(bx, by, Math.max(2, barW - 4), bh);
        }
        g.setFill(Color.web("#3a4554"));
        for (int i = 0; i < n; i++) {
            String label = bars.categories()[i];
            double bx = padLeft + i * barW;
            g.fillText(label, bx + 2, padTop + plotH + 14);
        }
    }

    /* ── Scalar ────────────────────────────────────────────────────────── */

    private static Rendered renderScalar(Visualisation.Scalar initial) {
        var valueLabel = new Label();
        valueLabel.setStyle("-fx-font-size: 28; -fx-font-weight: 600; -fx-text-fill: -studio-text;");
        var unitLabel = new Label();
        unitLabel.setStyle("-fx-font-size: 11; -fx-text-fill: -studio-text-tertiary;");
        var row = new HBox(8, valueLabel, unitLabel);
        row.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
        row.setPadding(new javafx.geometry.Insets(20, 0, 20, 0));

        var headerLabel = new Label();
        headerLabel.getStyleClass().add("viz-card-title");

        var card = new VBox(6, headerLabel, row);
        card.getStyleClass().add("viz-card");
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setPrefHeight(80);

        Consumer<Visualisation> updater = v -> {
            if (!(v instanceof Visualisation.Scalar s)) return;
            headerLabel.setText(s.title() == null ? "" : s.title());
            valueLabel.setText(String.format("%.6g", s.value()));
            unitLabel.setText(s.unit() == null ? "" : s.unit());
        };

        updater.accept(initial);
        return new Rendered(card, updater);
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static Label axisLabel(String text) {
        var l = new Label(text == null ? "" : text);
        l.setStyle("-fx-font-size: 10; -fx-text-fill: -studio-text-tertiary;");
        return l;
    }

    private static Label scaleLabel(double min, double max) {
        var l = new Label(String.format("[%.3g, %.3g]", min, max));
        l.setStyle("-fx-font-size: 10; -fx-text-fill: -studio-text-tertiary;");
        return l;
    }

    /** Five-stop viridis-ish colormap. t in [0, 1]. */
    private static Color viridis(double t) {
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double[] stops = { 0.00, 0.25, 0.50, 0.75, 1.00 };
        Color[] cols = {
            Color.web("#440154"),
            Color.web("#3b528b"),
            Color.web("#21908d"),
            Color.web("#5ec962"),
            Color.web("#fde725"),
        };
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i]) {
                double f = (t - stops[i - 1]) / (stops[i] - stops[i - 1]);
                return cols[i - 1].interpolate(cols[i], f);
            }
        }
        return cols[cols.length - 1];
    }
}
