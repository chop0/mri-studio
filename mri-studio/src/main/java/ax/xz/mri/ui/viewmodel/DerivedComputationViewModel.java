package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.simulation.PhaseMapComputer;
import ax.xz.mri.service.simulation.SignalTraceComputer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Background phase-map and signal-trace computations with generation/error tracking. */
public class DerivedComputationViewModel {
    public final ObjectProperty<PhaseMapData> phaseMapZ = new SimpleObjectProperty<>();
    public final ObjectProperty<PhaseMapData> phaseMapR = new SimpleObjectProperty<>();
    public final ObjectProperty<SignalTrace> signalTrace = new SimpleObjectProperty<>();
    public final BooleanProperty computing = new SimpleBooleanProperty(false);
    public final StringProperty errorMessage = new SimpleStringProperty();

    private final Executor executor;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong generation = new AtomicLong();

    public DerivedComputationViewModel() {
        this(createExecutor(), Platform::runLater, null);
    }

    DerivedComputationViewModel(Executor executor, Consumer<Runnable> uiDispatcher, Runnable disposer) {
        this.executor = executor;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
    }

    public void recompute(BlochData data, List<PulseSegment> pulse) {
        long currentGeneration = generation.incrementAndGet();
        if (data == null || pulse == null) {
            phaseMapZ.set(null);
            phaseMapR.set(null);
            signalTrace.set(null);
            errorMessage.set(null);
            computing.set(false);
            return;
        }

        computing.set(true);
        errorMessage.set(null);
        executor.execute(() -> {
            try {
                var nextZ = PhaseMapComputer.computePhaseZ(data, pulse);
                var nextR = PhaseMapComputer.computePhaseR(data, pulse);
                var nextSignal = SignalTraceComputer.compute(data, pulse);
                if (Thread.currentThread().isInterrupted()) return;
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    phaseMapZ.set(nextZ);
                    phaseMapR.set(nextR);
                    signalTrace.set(nextSignal);
                    computing.set(false);
                });
            } catch (Exception ex) {
                System.err.println("DerivedComputationViewModel: computation failed: " + ex.getMessage());
                ex.printStackTrace();
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    phaseMapZ.set(null);
                    phaseMapR.set(null);
                    signalTrace.set(null);
                    errorMessage.set(ex.getMessage());
                    computing.set(false);
                });
            }
        });
    }

    public void dispose() {
        disposer.run();
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "derived-compute");
            thread.setDaemon(true);
            return thread;
        });
    }
}
