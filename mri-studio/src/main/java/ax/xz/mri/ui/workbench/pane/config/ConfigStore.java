package ax.xz.mri.ui.workbench.pane.config;

import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Observable store over a {@link SimulationConfig} record.
 *
 * <p>Each field becomes a property that the UI binds bidirectionally; every
 * edit rebuilds the underlying record and republishes. A re-entrancy guard
 * prevents oscillation.
 */
public final class ConfigStore {
    public final ObjectProperty<SimulationConfig> config = new SimpleObjectProperty<>();

    public final DoubleProperty  t1Ms             = new SimpleDoubleProperty();
    public final DoubleProperty  t2Ms             = new SimpleDoubleProperty();
    public final DoubleProperty  gamma            = new SimpleDoubleProperty();
    public final DoubleProperty  sliceHalfMm      = new SimpleDoubleProperty();
    public final DoubleProperty  fovZMm           = new SimpleDoubleProperty();
    public final DoubleProperty  fovRMm           = new SimpleDoubleProperty();
    public final IntegerProperty nZ               = new SimpleIntegerProperty();
    public final IntegerProperty nR               = new SimpleIntegerProperty();
    public final DoubleProperty  referenceB0Tesla = new SimpleDoubleProperty();
    public final DoubleProperty  dtSeconds        = new SimpleDoubleProperty();
    public final ObjectProperty<ProjectNodeId> circuitId = new SimpleObjectProperty<>();

    public final DoubleBinding  larmorHz;
    public final DoubleBinding  nyquistHz;

    private boolean syncing;

    public ConfigStore(SimulationConfig initial) {
        larmorHz = Bindings.createDoubleBinding(
            () -> gamma.get() * referenceB0Tesla.get() / (2 * Math.PI),
            gamma, referenceB0Tesla);
        nyquistHz = Bindings.createDoubleBinding(
            () -> dtSeconds.get() > 0 ? 1.0 / (2 * dtSeconds.get()) : Double.POSITIVE_INFINITY,
            dtSeconds);

        writeFrom(initial);
        config.set(initial);

        t1Ms.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withT1Ms(n.doubleValue())));
        t2Ms.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withT2Ms(n.doubleValue())));
        gamma.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withGamma(n.doubleValue())));
        sliceHalfMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withSliceHalfMm(n.doubleValue())));
        fovZMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withFovZMm(n.doubleValue())));
        fovRMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withFovRMm(n.doubleValue())));
        nZ.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withNZ(n.intValue())));
        nR.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withNR(n.intValue())));
        referenceB0Tesla.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withReferenceB0Tesla(n.doubleValue())));
        dtSeconds.addListener((obs, o, n) -> rebuildFromProperties(c -> {
            double v = n.doubleValue();
            return v > 0 ? c.withDtSeconds(v) : c;
        }));
        circuitId.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withCircuitId(n)));

        config.addListener((obs, oldC, newC) -> {
            if (syncing || newC == null) return;
            syncing = true;
            try { writeFrom(newC); } finally { syncing = false; }
        });
    }

    public void setConfig(SimulationConfig c) {
        if (c == null || c.equals(config.get())) return;
        config.set(c);
    }

    public SimulationConfig getConfig() {
        return config.get();
    }

    private void rebuildFromProperties(java.util.function.UnaryOperator<SimulationConfig> delta) {
        if (syncing) return;
        var current = config.get();
        if (current == null) return;
        var next = delta.apply(current);
        if (next == null || next.equals(current)) return;
        syncing = true;
        try { config.set(next); } finally { syncing = false; }
    }

    private void writeFrom(SimulationConfig c) {
        if (c == null) return;
        t1Ms.set(c.t1Ms());
        t2Ms.set(c.t2Ms());
        gamma.set(c.gamma());
        sliceHalfMm.set(c.sliceHalfMm());
        fovZMm.set(c.fovZMm());
        fovRMm.set(c.fovRMm());
        nZ.set(c.nZ());
        nR.set(c.nR());
        referenceB0Tesla.set(c.referenceB0Tesla());
        dtSeconds.set(c.dtSeconds());
        circuitId.set(c.circuitId());
    }
}
