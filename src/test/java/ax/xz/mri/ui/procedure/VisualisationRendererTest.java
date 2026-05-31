package ax.xz.mri.ui.procedure;

import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.support.FxTestSupport;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the {@link VisualisationRenderer}'s update-in-place contract.
 *
 * <p>The procedure pane re-emits visualisations 60×/s while a procedure
 * runs. Before the in-place-update refactor every emission tore down and
 * rebuilt the chart node, producing a constant flicker. This test asserts
 * that calling {@code update.accept(newViz)} on an existing {@link
 * VisualisationRenderer.Rendered} both mutates the data and reuses the
 * <em>same</em> chart node — i.e. neither the {@link Node} returned by
 * {@code render} nor the {@link LineChart} inside it is replaced by the
 * update.
 *
 * <p>Catches: any future change that accidentally rebuilds the chart on
 * update (e.g. calling {@code render} from inside {@code update}).
 */
final class VisualisationRendererTest {

    @Test
    void lineUpdaterReplacesSeriesDataInPlaceAndKeepsChartNode() {
        FxTestSupport.runOnFxThread(() -> {
            var initial = new Visualisation.Line(
                "convergence", "Convergence",
                "iter", "rmse (T)",
                List.of(new Visualisation.Line.Series("rmse",
                    new double[]{0, 1, 2}, new double[]{1e-7, 8e-8, 5e-8})));
            var rendered = VisualisationRenderer.render(initial);
            assertNotNull(rendered.node());
            var chart = findLineChart(rendered.node());
            assertNotNull(chart, "renderer must include a LineChart for Line viz");
            assertEquals(1, chart.getData().size());
            assertEquals(3, chart.getData().get(0).getData().size(),
                "initial series should carry 3 points");

            // Re-emit with the same id and an extra data point — the
            // updater must mutate the existing chart, not replace it.
            var updated = new Visualisation.Line(
                "convergence", "Convergence",
                "iter", "rmse (T)",
                List.of(new Visualisation.Line.Series("rmse",
                    new double[]{0, 1, 2, 3, 4},
                    new double[]{1e-7, 8e-8, 5e-8, 3e-8, 2e-8})));
            rendered.update().accept(updated);

            var chartAfter = findLineChart(rendered.node());
            assertSame(chart, chartAfter,
                "the LineChart node must persist across updates — recreating it causes flicker");
            assertEquals(1, chart.getData().size());
            assertEquals(5, chart.getData().get(0).getData().size(),
                "series data should grow in place to 5 points");
            // The x values must reflect the new data (the renderer rescales
            // via SI prefix; the last point's raw x is 4 → display position 4
            // unscaled because there's no unit on the "iter" axis).
            var last = chart.getData().get(0).getData().get(4);
            assertEquals(4.0, ((Number) last.getXValue()).doubleValue(), 1e-9);
        });
    }

    @Test
    void lineUpdaterTolerantToWrongKindEmission() {
        // If the procedure (incorrectly) re-emits a different Visualisation
        // kind under the same id, the updater silently no-ops instead of
        // throwing; the host can then create a fresh card if it cares.
        FxTestSupport.runOnFxThread(() -> {
            var rendered = VisualisationRenderer.render(new Visualisation.Line(
                "x", "X", "a", "b",
                List.of(new Visualisation.Line.Series("y",
                    new double[]{0}, new double[]{0}))));
            var chartBefore = findLineChart(rendered.node());
            assertNotNull(chartBefore);
            assertDoesNotThrow(() -> rendered.update().accept(
                new Visualisation.Scalar("x", "X", 1.0, "T")));
            assertSame(chartBefore, findLineChart(rendered.node()),
                "wrong-kind update must not tear the existing chart down");
        });
    }

    @Test
    void scalarUpdaterReusesLabelsAcrossEmissions() {
        FxTestSupport.runOnFxThread(() -> {
            var rendered = VisualisationRenderer.render(new Visualisation.Scalar(
                "peak", "Peak", 1.0, "T"));
            var firstNode = rendered.node();
            assertNotNull(firstNode);
            rendered.update().accept(new Visualisation.Scalar("peak", "Peak", 2.0, "T"));
            assertSame(firstNode, rendered.node(),
                "scalar card node must be reused across updates");
        });
    }

    /**
     * Locks in the renderer's decimation contract: when a procedure emits a
     * series longer than the chart can efficiently redraw, the renderer
     * subsamples to a bounded point count. Without this, a 10 000-iter
     * adaptive run would crawl in the tail as each per-tick redraw pushed
     * the full history into JavaFX's chart layout.
     */
    @Test
    void lineUpdaterDecimatesLargeSeries() {
        FxTestSupport.runOnFxThread(() -> {
            int n = 10_000;
            double[] x = new double[n], y = new double[n];
            for (int i = 0; i < n; i++) { x[i] = i; y[i] = Math.sin(i * 0.01); }
            var rendered = VisualisationRenderer.render(new Visualisation.Line(
                "huge", "huge", "i", "v",
                List.of(new Visualisation.Line.Series("y", x, y))));
            var chart = findLineChart(rendered.node());
            assertNotNull(chart);
            int rendered_points = chart.getData().get(0).getData().size();
            assertTrue(rendered_points <= 1600,
                "10k-point series must be decimated; got " + rendered_points + " rendered points");
            // The final point should still be present so the live tail is visible.
            var last = chart.getData().get(0).getData().get(rendered_points - 1);
            assertEquals((double)(n - 1), ((Number) last.getXValue()).doubleValue(), 1.0,
                "the last data point must be included so the live tail stays visible");
        });
    }

    /** Recursive search for the first {@link LineChart} under the given node. */
    private static LineChart<?, ?> findLineChart(Node root) {
        if (root instanceof LineChart<?, ?> chart) return chart;
        if (root instanceof javafx.scene.Parent parent) {
            for (var c : parent.getChildrenUnmodifiable()) {
                var hit = findLineChart(c);
                if (hit != null) return hit;
            }
        }
        if (root instanceof StackPane stack) {
            for (var c : stack.getChildren()) {
                var hit = findLineChart(c);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
