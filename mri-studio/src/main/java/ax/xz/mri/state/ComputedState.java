package ax.xz.mri.state;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.simulation.PhaseMapComputer;
import ax.xz.mri.service.simulation.SignalTraceComputer;
import javafx.application.Platform;
import javafx.beans.property.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asynchronously recomputes phase maps and signal trace whenever the pulse changes.
 * Results are pushed back to the FX thread via Platform.runLater.
 */
public class ComputedState {
    public final ObjectProperty<PhaseMapData> phaseMapZ   = new SimpleObjectProperty<>();
    public final ObjectProperty<PhaseMapData> phaseMapR   = new SimpleObjectProperty<>();
    public final ObjectProperty<SignalTrace>  signalTrace  = new SimpleObjectProperty<>();
    public final BooleanProperty              computing    = new SimpleBooleanProperty(false);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "bloch-compute");
        t.setDaemon(true);
        return t;
    });
    private Future<?> pending;

    public void recompute(BlochData data, List<PulseSegment> pulse) {
        if (pending != null) pending.cancel(true);
        if (data == null || pulse == null) {
            phaseMapZ.set(null); phaseMapR.set(null); signalTrace.set(null);
            return;
        }
        computing.set(true);
        pending = exec.submit(() -> {
            var pmZ = PhaseMapComputer.computePhaseZ(data, pulse);
            var pmR = PhaseMapComputer.computePhaseR(data, pulse);
            var sig = SignalTraceComputer.compute(data, pulse);
            Platform.runLater(() -> {
                phaseMapZ.set(pmZ);
                phaseMapR.set(pmR);
                signalTrace.set(sig);
                computing.set(false);
            });
        });
    }
}
