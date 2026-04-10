package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.SimulationConfigDocument;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages live simulation for the sequence editor.
 *
 * <p>This is a thin bridge: it bakes the clip sequence, builds synthetic BlochData
 * from the active config, and pushes the result through
 * {@link StudioSession#loadSimulationResult} — the single entry point that feeds
 * all analysis panes. No local models, no generation counters, no races.
 */
public final class SequenceSimulationSession {
    private static final long DEBOUNCE_MS = 500;

    public final ObjectProperty<SimulationConfigDocument> activeConfigDoc = new SimpleObjectProperty<>();
    public final ObjectProperty<SimulationConfig> activeConfig = new SimpleObjectProperty<>();
    public final BooleanProperty autoSimulate = new SimpleBooleanProperty(true);
    public final BooleanProperty simulating = new SimpleBooleanProperty(false);
    public final BooleanProperty stale = new SimpleBooleanProperty(false);

    public final SequenceEditSession editSession;
    private final StudioSession studioSession;
    private Timer debounceTimer;
    private boolean disposed;

    private final ProjectSessionViewModel projectSession;

    public SequenceSimulationSession(SequenceEditSession editSession, StudioSession studioSession) {
        this.editSession = editSession;
        this.studioSession = studioSession;
        this.projectSession = studioSession.project;

        editSession.revision.addListener((obs, o, n) -> {
            stale.set(true);
            if (autoSimulate.get()) scheduleSimulation();
        });

        activeConfig.addListener((obs, o, n) -> {
            stale.set(true);
            if (autoSimulate.get()) scheduleSimulation();
        });

        activeConfigDoc.addListener((obs, o, n) -> {
            if (n != null) activeConfig.set(n.config());
        });
    }

    public void loadConfig(SimulationConfigDocument doc) {
        activeConfigDoc.set(doc);
        if (doc != null) activeConfig.set(doc.config());
        simulate();
    }

    public void updateConfigLive(SimulationConfig newConfig) {
        activeConfig.set(newConfig);
    }

    /** Run simulation: bake sequence, build BlochData, push to analysis panes. */
    public void simulate() {
        if (disposed) return;
        cancelPendingDebounce();

        var config = activeConfig.get();
        if (config == null) return;
        var orig = editSession.originalDocument.get();
        if (orig == null) return;

        simulating.set(true);

        // Bake the clip sequence
        var doc = editSession.toDocument();
        var segments = doc.segments();
        var pulse = doc.pulse();
        var data = BlochDataFactory.build(config, segments, projectSession.repository.get());

        // Push through the single unified path — this is the ONLY place data reaches the panes
        studioSession.loadSimulationResult(data, pulse);

        stale.set(false);
        simulating.set(false);
    }

    private void scheduleSimulation() {
        cancelPendingDebounce();
        debounceTimer = new Timer("sim-debounce", true);
        debounceTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> simulate());
            }
        }, DEBOUNCE_MS);
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
    }
}
