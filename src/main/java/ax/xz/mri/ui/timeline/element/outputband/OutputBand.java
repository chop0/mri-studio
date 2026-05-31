package ax.xz.mri.ui.timeline.element.outputband;

import ax.xz.mri.model.sequence.RunContext;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import javafx.collections.SetChangeListener;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * The read-only probe band beneath the editable lanes. Composes one
 * {@link OutputRow} per enabled probe (sim and hardware combined), shares a
 * single vertical scale across all rows so the user can compare an
 * 0.8&nbsp;V raw RF cosine against an 0.5&nbsp;V baseband I directly, and
 * rebuilds reactively when the user toggles probes in the toolbar's outputs
 * popover.
 *
 * <p>The plan called for a {@link javafx.scene.control.SplitPane} between the
 * editable area and this band. The OutputBand itself is just the rows; the
 * SplitPane wraps it at the {@code TimelineRoot} level so the resize handle
 * lives between the two regions, not inside one of them.
 */
public final class OutputBand extends VBox {
    private final EditSession session;
    private final TimelineMetrics metrics;
    private final Map<RowKey, OutputRow> rows = new HashMap<>();

    public OutputBand(EditSession session, TimelineMetrics metrics) {
        this.session = session;
        this.metrics = metrics;
        getStyleClass().add("output-band");
        setSpacing(0);

        Runnable rebuild = this::rebuildRows;
        session.enabledSimOutputs.addListener(
            (SetChangeListener<String>) c -> rebuild.run());
        session.enabledHardwareOutputs.addListener(
            (SetChangeListener<String>) c -> rebuild.run());
        session.lastSimulationTraces.addListener((obs, o, n) -> {
            pushTraces(RunContext.SIMULATION, n);
            recomputeSharedScale();
        });
        session.lastHardwareTraces.addListener((obs, o, n) -> {
            pushTraces(RunContext.HARDWARE, n);
            recomputeSharedScale();
        });

        rebuildRows();
    }

    private void rebuildRows() {
        getChildren().clear();
        rows.clear();

        for (var probe : session.enabledSimOutputs) {
            var row = new OutputRow(metrics, RunContext.SIMULATION, probe);
            rows.put(new RowKey(RunContext.SIMULATION, probe), row);
            getChildren().add(row);
        }
        for (var probe : session.enabledHardwareOutputs) {
            var row = new OutputRow(metrics, RunContext.HARDWARE, probe);
            rows.put(new RowKey(RunContext.HARDWARE, probe), row);
            getChildren().add(row);
        }

        pushTraces(RunContext.SIMULATION, session.lastSimulationTraces.get());
        pushTraces(RunContext.HARDWARE, session.lastHardwareTraces.get());
        recomputeSharedScale();
    }

    private void pushTraces(RunContext context, MultiProbeSignalTrace bundle) {
        for (var entry : rows.entrySet()) {
            if (entry.getKey().context() != context) continue;
            var name = entry.getKey().probeName();
            var trace = bundle == null ? null : bundle.byProbe().get(name);
            entry.getValue().setTrace(trace);
        }
    }

    private void recomputeSharedScale() {
        double max = 0;
        for (var bundle : new MultiProbeSignalTrace[]{
                session.lastSimulationTraces.get(),
                session.lastHardwareTraces.get()}) {
            if (bundle == null) continue;
            for (var trace : bundle.byProbe().values()) {
                for (var p : trace.points()) {
                    double mag = Math.hypot(p.real(), p.imag());
                    if (mag > max) max = mag;
                }
            }
        }
        double finalMax = Math.max(1, max);
        for (var row : rows.values()) row.setSharedMaxAbs(finalMax);
    }

    private record RowKey(RunContext context, String probeName) {}
}
