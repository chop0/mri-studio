package ax.xz.mri.ui.workbench.pane.config;

import module ax.xz.mri;
import module javafx.base;

import ax.xz.mri.project.ProjectNodeId;

/**
 * Observable store over a {@link SimulationConfig} record.
 *
 * <p>Each editable field becomes a property the UI binds bidirectionally;
 * every edit rebuilds the underlying record and republishes through
 * {@link #config}. A re-entrancy guard prevents oscillation.
 *
 * <p>Spatial layout (extent + resolution) does NOT live here — it's a
 * property of the substance the circuit references. The Larmor read-out
 * uses {@link #gammaRadPerSecPerTesla}, which the editor pane pushes here
 * after walking the project state to resolve the dominant substance.
 */
public final class ConfigStore {
    public final ObjectProperty<SimulationConfig> config = new SimpleObjectProperty<>();

    public final DoubleProperty referenceB0Tesla = new SimpleDoubleProperty();
    public final DoubleProperty dtSeconds = new SimpleDoubleProperty();
    public final ObjectProperty<ProjectNodeId> circuitId = new SimpleObjectProperty<>();

    /**
     * NV simulation-method knobs (only meaningful when the circuit references an
     * NV ensemble). The cap bounds how many NVs couple jointly; the cutoff
     * (in nm) decides which pairs couple. Cap 1 ⇒ independent classical NVs.
     */
    public final IntegerProperty nvMaxClusterSize = new SimpleIntegerProperty(1);
    public final DoubleProperty nvCouplingCutoffNm = new SimpleDoubleProperty(0);

    /**
     * γ to use when computing {@link #larmorHz}. Owned by the editor pane —
     * it walks the project state to find the substance the circuit
     * references and pushes its gyromagnetic ratio here. {@code NaN} means
     * "no substance" → larmorHz also reads NaN, and the UI renders "—".
     */
    public final DoubleProperty gammaRadPerSecPerTesla = new SimpleDoubleProperty(Double.NaN);

    public final DoubleBinding larmorHz;
    public final DoubleBinding nyquistHz;

    private boolean syncing;

    public ConfigStore(SimulationConfig initial) {
        larmorHz = Bindings.createDoubleBinding(
            () -> gammaRadPerSecPerTesla.get() * referenceB0Tesla.get() / (2 * Math.PI),
            referenceB0Tesla, gammaRadPerSecPerTesla);
        nyquistHz = Bindings.createDoubleBinding(
            () -> dtSeconds.get() > 0 ? 1.0 / (2 * dtSeconds.get()) : Double.POSITIVE_INFINITY,
            dtSeconds);

        writeFrom(initial);
        config.set(initial);

        referenceB0Tesla.addListener((obs, o, n) -> rebuild(c -> c.withReferenceB0Tesla(n.doubleValue())));
        dtSeconds.addListener((obs, o, n) -> rebuild(c -> {
            double v = n.doubleValue();
            return v > 0 ? c.withDtSeconds(v) : c;
        }));
        circuitId.addListener((obs, o, n) -> rebuild(c -> c.withCircuitId(n)));
        nvMaxClusterSize.addListener((obs, o, n) -> rebuild(this::applyNvMethod));
        nvCouplingCutoffNm.addListener((obs, o, n) -> rebuild(this::applyNvMethod));

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

    private void rebuild(java.util.function.UnaryOperator<SimulationConfig> delta) {
        if (syncing) return;
        var current = config.get();
        if (current == null) return;
        var next = delta.apply(current);
        if (next == null || next.equals(current)) return;
        syncing = true;
        try {
            config.set(next);
        } finally {
            syncing = false;
        }
    }

    private void writeFrom(SimulationConfig c) {
        if (c == null) return;
        referenceB0Tesla.set(c.referenceB0Tesla());
        dtSeconds.set(c.dtSeconds());
        circuitId.set(c.circuitId());
        if (c.methods() != null
            && c.methods().nv() instanceof NvSimulationMethod.ClusteredQubitHamiltonian h) {
            nvMaxClusterSize.set(h.maxClusterSize());
            nvCouplingCutoffNm.set(h.couplingCutoffMetres() * 1e9);
        }
    }

    /** Rebuild the config's NV method from the current cap + cutoff knobs. */
    private SimulationConfig applyNvMethod(SimulationConfig c) {
        int cap = Math.max(1, nvMaxClusterSize.get());
        double cutoffM = Math.max(0, nvCouplingCutoffNm.get()) * 1e-9;
        return c.withMethods(new SimulationMethods(
            new NvSimulationMethod.ClusteredQubitHamiltonian(cap, cutoffM)));
    }
}
