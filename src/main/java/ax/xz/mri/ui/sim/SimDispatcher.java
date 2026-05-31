package ax.xz.mri.ui.sim;

import ax.xz.mri.ui.time.Generation;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coalesces dirty signals into simulation submissions.
 *
 * <p>Every input that affects sim output funnels through {@link #markDirty()}.
 * Closely-spaced calls collapse into a single submission after {@code DEBOUNCE_MS},
 * so a per-pixel mouse drag produces one bake instead of sixty. Each submission
 * captures a fresh {@link Generation} token; {@link SimRunner} consults that token
 * before publishing, so a slow run can never overwrite a fresh one.
 *
 * <p>The dispatcher knows nothing about edit sessions or projects: it consumes
 * a {@link Supplier} that builds the latest {@link SimRequest} on demand. The
 * supplier returning {@code null} is the signal that there's nothing ready to
 * simulate (e.g. no active config). Wiring the supplier and the dirty-signal
 * subscriptions to the rest of the application lives in
 * {@link ax.xz.mri.ui.viewmodel.StudioSession#newSimDispatcher}.
 */
public final class SimDispatcher {
    private static final long DEBOUNCE_MS = 200;

    public final BooleanProperty autoSimulate = new SimpleBooleanProperty(true);
    public final ObjectProperty<SimState> state = new SimpleObjectProperty<>(SimState.IDLE);
    public final ObjectProperty<SimResult> result = new SimpleObjectProperty<>();

    private final Supplier<SimRequest> requestSupplier;
    private final SimSubmitter submitter;
    private final Generation generation;
    private final Consumer<SimResult> publish;
    private final BiConsumer<String, Throwable> errorReporter;

    private Timer debounceTimer;
    private boolean disposed;

    public SimDispatcher(Supplier<SimRequest> requestSupplier,
                         SimSubmitter submitter,
                         Generation generation,
                         Consumer<SimResult> publish,
                         BiConsumer<String, Throwable> errorReporter) {
        this.requestSupplier = requestSupplier;
        this.submitter = submitter;
        this.generation = generation;
        this.publish = publish;
        this.errorReporter = errorReporter;
    }

    /** Schedule a debounced simulation run. Safe to call repeatedly. */
    public void markDirty() {
        if (disposed) return;
        generation.bump();
        state.set(SimState.PENDING);
        if (autoSimulate.get()) scheduleDebounced();
    }

    /** Run a simulation now, cancelling any pending debounce. */
    public void simulate() {
        if (disposed) return;
        cancelPendingDebounce();
        submitNow();
    }

    private void scheduleDebounced() {
        cancelPendingDebounce();
        debounceTimer = new Timer("sim-debounce", true);
        debounceTimer.schedule(new TimerTask() {
            @Override public void run() {
                Platform.runLater(SimDispatcher.this::submitNow);
            }
        }, DEBOUNCE_MS);
    }

    private void submitNow() {
        if (disposed) return;
        var request = requestSupplier.get();
        if (request == null) {
            state.set(SimState.IDLE);
            return;
        }
        state.set(SimState.RUNNING);
        submitter.submit(request, this::onResult, this::onError);
    }

    private void onResult(SimResult result) {
        this.result.set(result);
        publish.accept(result);
        state.set(SimState.IDLE);
    }

    private void onError(Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        errorReporter.accept(message, failure);
        state.set(new SimState.Failed(message));
    }

    private void cancelPendingDebounce() {
        if (debounceTimer != null) {
            debounceTimer.cancel();
            debounceTimer = null;
        }
    }

    public void dispose() {
        disposed = true;
        cancelPendingDebounce();
        submitter.dispose();
    }
}
