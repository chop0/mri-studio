package ax.xz.mri.model.simulation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signal traces produced by all receive coils over the course of one simulation.
 *
 * <p>The iteration order of {@link #byCoil()} matches the order of
 * {@link SimulationConfig#receiveCoils()} at build time; the first entry is the
 * primary coil (surfaced as {@link #primary()}) used by downstream analysis
 * panes that only ever display a single trace.
 */
public record MultiCoilSignalTrace(Map<String, SignalTrace> byCoil, String primaryCoilName) {
    public MultiCoilSignalTrace {
        byCoil = Map.copyOf(new LinkedHashMap<>(byCoil));
    }

    public SignalTrace primary() {
        if (primaryCoilName == null) return null;
        return byCoil.get(primaryCoilName);
    }

    public static MultiCoilSignalTrace empty() {
        return new MultiCoilSignalTrace(Map.of(), null);
    }
}
