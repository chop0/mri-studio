package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-document derived state — at v1, just the primary signal trace.
 *
 * <p>The simulator pipeline ({@link ax.xz.mri.ui.sim.SimRunner}) calls
 * {@link CompiledSimulation#runMultiProbe()} once on its worker thread and
 * publishes the result on {@link ax.xz.mri.ui.sim.SimResult#traces()}; this
 * model reuses that result by default. Callers without a precomputed trace
 * (importers, hardware-only sessions) can submit a fresh
 * {@link MultiProbeSignalTrace} via {@link #acceptProbeTraces}.
 */
public class DerivedComputationViewModel {
    public final ObjectProperty<SignalTrace> signalTrace = new SimpleObjectProperty<>();
    public final BooleanProperty computing = new SimpleBooleanProperty(false);
    public final StringProperty errorMessage = new SimpleStringProperty();

    private final Executor executor;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong generation = new AtomicLong();
    private Consumer<Throwable> errorSink = ex -> { };

    public DerivedComputationViewModel() {
        this(createExecutor(), Platform::runLater, null);
    }

    DerivedComputationViewModel(Executor executor, Consumer<Runnable> uiDispatcher, Runnable disposer) {
        this.executor = executor;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
    }

    public void setErrorSink(Consumer<Throwable> sink) {
        this.errorSink = sink != null ? sink : ex -> { };
    }

    /** Recompute the signal trace from the simulation. */
    public void recompute(CompiledSimulation simulation, List<PulseSegment> pulse) {
        recompute(simulation, pulse, null);
    }

    /**
     * Recompute / replace the signal trace. {@code precomputedPrimary} is the
     * typical hot-path input — the simulator already computed it on its
     * worker thread. When null, we fall back to running
     * {@link CompiledSimulation#runMultiProbe()} on the background thread.
     */
    public void recompute(CompiledSimulation simulation, List<PulseSegment> pulse, SignalTrace precomputedPrimary) {
        long currentGeneration = generation.incrementAndGet();
        if (simulation == null || pulse == null) {
            signalTrace.set(null);
            errorMessage.set(null);
            computing.set(false);
            return;
        }

        if (precomputedPrimary != null) {
            signalTrace.set(precomputedPrimary);
            errorMessage.set(null);
            computing.set(false);
            return;
        }

        computing.set(true);
        errorMessage.set(null);
        executor.execute(() -> {
            try {
                var traces = simulation.runMultiProbe();
                if (Thread.currentThread().isInterrupted()) return;
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    signalTrace.set(traces == null ? null : traces.primary());
                    computing.set(false);
                });
            } catch (Exception ex) {
                errorSink.accept(ex);
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    signalTrace.set(null);
                    errorMessage.set(ex.getMessage());
                    computing.set(false);
                });
            }
        });
    }

    /** Replace the derived state with traces produced directly by a hardware device. */
    public void acceptProbeTraces(MultiProbeSignalTrace traces) {
        long currentGeneration = generation.incrementAndGet();
        uiDispatcher.accept(() -> {
            if (currentGeneration != generation.get()) return;
            signalTrace.set(traces == null ? null : traces.primary());
            errorMessage.set(null);
            computing.set(false);
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
