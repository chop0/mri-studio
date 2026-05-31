package ax.xz.mri.model.simulation;

import ax.xz.mri.dsl.ProbeKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Signal traces produced by all probes during one simulation.
 *
 * <p>Iteration order matches the circuit's probe declaration order; the first
 * entry is the primary probe surfaced to the magnitude trace pane via
 * {@link #primary()}.
 *
 * <p>Scripts read probes via {@link #read(ProbeKey)} — typed handle, throws
 * with the available probe set if the key isn't present (much friendlier
 * than a silent {@code null} from {@code byProbe().get(...)}).
 */
public record MultiProbeSignalTrace(Map<String, SignalTrace> byProbe, String primaryProbeName) {
    public MultiProbeSignalTrace {
        byProbe = Map.copyOf(new LinkedHashMap<>(byProbe == null ? Map.of() : byProbe));
    }

    public SignalTrace primary() {
        if (primaryProbeName == null) return null;
        return byProbe.get(primaryProbeName);
    }

    /**
     * Look up a probe by {@link ProbeKey}. Throws {@link NoSuchElementException}
     * with the available probe set if the key isn't present — preferred over
     * {@code byProbe().get(...)} which silently returns {@code null}.
     */
    public SignalTrace read(ProbeKey key) {
        if (key == null) throw new IllegalArgumentException("ProbeKey must not be null");
        var trace = byProbe.get(key.name());
        if (trace == null) throw new NoSuchElementException(
            "No probe named '" + key.name() + "' in trace. Available: " + byProbe.keySet());
        return trace;
    }

    /** Lenient lookup — returns empty when the probe isn't present. */
    public Optional<SignalTrace> find(ProbeKey key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(byProbe.get(key.name()));
    }

    public static MultiProbeSignalTrace empty() {
        return new MultiProbeSignalTrace(Map.of(), null);
    }
}
