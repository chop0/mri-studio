package ax.xz.mri.state;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Debounced background save with atomic-rename crash safety.
 *
 * <p>Every state change calls {@link #schedule(ProjectState)}. The autosaver
 * cancels any pending write and reschedules 200ms in the future. The save
 * task runs on a single-thread executor, captures the {@link ProjectState}
 * snapshot reference, and writes via the supplied writer. If
 * {@link #flush()} is called (e.g. on app close), the autosaver blocks until
 * any pending write has completed.
 *
 * <p>The {@link #savingProperty()} flips true while a write is in flight —
 * useful as a UI indicator while preserving the user's "no unsaved changes"
 * mental model.
 */
public final class Autosaver {
    private static final long DEBOUNCE_MS = 200;

    private final ScheduledExecutorService executor;
    private final ProjectStateWriter writer;
    private final Consumer<Throwable> errorSink;
    private final UndoLogPersistence undoLogIO = new UndoLogPersistence();
    private final BooleanProperty saving = new SimpleBooleanProperty(false);
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final AtomicReference<ProjectState> latest = new AtomicReference<>();
    private final AtomicReference<java.util.Deque<Mutation>> latestLog = new AtomicReference<>();
    private volatile Path projectRoot;

    public interface ProjectStateWriter {
        void write(ProjectState state, Path root) throws IOException;
    }

    public Autosaver(ProjectStateWriter writer, Consumer<Throwable> errorSink) {
        this.writer = writer;
        this.errorSink = errorSink == null ? ex -> {} : errorSink;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "autosaver");
            t.setDaemon(true);
            return t;
        });
    }

    public void setProjectRoot(Path root) {
        this.projectRoot = root;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public ReadOnlyBooleanProperty savingProperty() {
        return saving;
    }

    /**
     * Schedule a write for {@code state}. Cancels any pending write; the new
     * write fires {@link #DEBOUNCE_MS} after the last call to this method.
     * If no project root is set, this is a no-op.
     */
    public void schedule(ProjectState state) {
        latest.set(state);
        if (projectRoot == null) return;
        var prev = pending.getAndSet(null);
        if (prev != null) prev.cancel(false);
        var future = executor.schedule(this::performWrite, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.set(future);
    }

    /** Update the undo log snapshot to be persisted alongside the next write. */
    public void scheduleLog(java.util.Deque<Mutation> log) {
        latestLog.set(log);
    }

    /**
     * Block until any pending write completes. Safe to call on the FX thread
     * but will block; intended for app-close shutdown.
     */
    public void flush() {
        var p = pending.getAndSet(null);
        if (p != null) {
            p.cancel(false);
            // Run synchronously to ensure latest is on disk.
            performWrite();
        }
    }

    public void shutdown() {
        flush();
        executor.shutdown();
    }

    private void performWrite() {
        var state = latest.get();
        var root = projectRoot;
        if (state == null || root == null) return;
        notifySaving(true);
        try {
            writer.write(state, root);
            var log = latestLog.get();
            if (log != null) {
                try {
                    undoLogIO.write(log, root);
                } catch (Throwable ex) {
                    errorSink.accept(ex);
                }
            }
        } catch (Throwable ex) {
            errorSink.accept(ex);
        } finally {
            notifySaving(false);
        }
    }

    private void notifySaving(boolean v) {
        if (Platform.isFxApplicationThread()) {
            saving.set(v);
        } else {
            Platform.runLater(() -> saving.set(v));
        }
    }

    public BiConsumer<ProjectState, Path> asImmediateWriter() {
        return (s, p) -> {
            try {
                writer.write(s, p);
            } catch (IOException ex) {
                errorSink.accept(ex);
            }
        };
    }
}
