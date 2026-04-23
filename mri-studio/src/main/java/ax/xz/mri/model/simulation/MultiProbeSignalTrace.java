package ax.xz.mri.model.simulation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signal traces produced by all probes during one simulation.
 *
 * <p>Iteration order matches the circuit's probe declaration order; the first
 * entry is the primary probe surfaced to the magnitude trace pane via
 * {@link #primary()}.
 */
public record MultiProbeSignalTrace(Map<String, SignalTrace> byProbe, String primaryProbeName) {
    public MultiProbeSignalTrace {
        byProbe = Map.copyOf(new LinkedHashMap<>(byProbe == null ? Map.of() : byProbe));
    }

    public SignalTrace primary() {
        if (primaryProbeName == null) return null;
        return byProbe.get(primaryProbeName);
    }

    public static MultiProbeSignalTrace empty() {
        return new MultiProbeSignalTrace(Map.of(), null);
    }
}
