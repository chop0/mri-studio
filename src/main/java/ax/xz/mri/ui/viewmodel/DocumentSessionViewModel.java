package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.scenario.SimulationOutput;
import ax.xz.mri.model.sequence.PulseSegment;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;

/**
 * Active document state shared across analysis panes.
 *
 * <p>The canonical handle is {@link #runResult} — sealed over {@code Simulation}
 * and {@code Hardware}. Two derived properties track its components for the
 * panes that don't need to know which kind of run produced the data:
 * <ul>
 *   <li>{@link #simulationOutput} — the simulator's spatial state. Null when
 *       the current result is a hardware run; spatial panes (phase maps,
 *       Bloch sphere, cross-section) guard against this.</li>
 *   <li>{@link #currentPulse} — the pulse list, populated whenever a result
 *       is loaded; consumed by trace and timeline panes.</li>
 * </ul>
 */
public class DocumentSessionViewModel {
    /** Latest run that produced data for the analysis panes. Null until something has been loaded. */
    public final ObjectProperty<RunResult> runResult = new SimpleObjectProperty<>();

    /** Simulator spatial state when the active run is a {@code Simulation}, else null. */
    public final ObjectProperty<SimulationOutput> simulationOutput = new SimpleObjectProperty<>();

    /** Pulse list from the active result, or null when no run is loaded. */
    public final ObjectProperty<List<PulseSegment>> currentPulse = new SimpleObjectProperty<>();

    public DocumentSessionViewModel() {
        runResult.addListener((obs, oldResult, newResult) -> {
            if (newResult == null) {
                simulationOutput.set(null);
                currentPulse.set(null);
                return;
            }
            currentPulse.set(newResult.pulse());
            simulationOutput.set(newResult instanceof RunResult.Simulation sim ? sim.output() : null);
        });
    }

    public void clearDocument() {
        runResult.set(null);
    }
}
