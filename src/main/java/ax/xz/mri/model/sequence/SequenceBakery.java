package ax.xz.mri.model.sequence;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.state.ProjectState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoised bakery for {@link SequenceDocument}s.
 *
 * <p>{@link SequenceDocument} only stores the canonical authoring inputs —
 * a {@link ClipSequence} plus the simulation/hardware config FKs. The
 * simulator and hardware run paths need the per-step baked output
 * ({@code segments} + {@code pulse}). Computing it is non-trivial (calls
 * {@link ClipBaker#bake}), so this service caches by content-equality on
 * {@code (clipSequence, circuit)}.
 *
 * <p>Records' value-based {@code equals} makes the cache key reliable: a
 * circuit edit produces a new circuit instance not equal to the previous,
 * so the next bake call re-bakes; an unchanged circuit hits the cache.
 */
public final class SequenceBakery {

    /** Identity key — content equality on the two inputs. */
    private record Key(ClipSequence clipSequence, CircuitDocument circuit) {}

    /** Result of baking — segment list + per-step pulse list. */
    public record BakedSequence(java.util.List<Segment> segments, java.util.List<PulseSegment> pulseSegments) {
        public BakedSequence {
            segments = java.util.List.copyOf(segments);
            pulseSegments = java.util.List.copyOf(pulseSegments);
        }
        /** Empty bake — used when there's nothing to simulate yet. */
        public static final BakedSequence EMPTY =
            new BakedSequence(java.util.List.of(), java.util.List.of());
    }

    private final Map<Key, BakedSequence> cache = new ConcurrentHashMap<>();

    /** Bake a sequence's clipSequence against its circuit. */
    public BakedSequence bake(SequenceDocument seq, ProjectState state) {
        if (seq == null) return BakedSequence.EMPTY;
        var circuit = resolveCircuit(seq, state);
        return bake(seq.clipSequence(), circuit);
    }

    public BakedSequence bake(ClipSequence clipSequence, CircuitDocument circuit) {
        if (clipSequence == null) return BakedSequence.EMPTY;
        var key = new Key(clipSequence, circuit);
        return cache.computeIfAbsent(key, k -> {
            var r = ClipBaker.bake(k.clipSequence(), k.circuit());
            return new BakedSequence(r.segments(), r.pulseSegments());
        });
    }

    /** Drop the entire cache. */
    public void clear() {
        cache.clear();
    }

    private static CircuitDocument resolveCircuit(SequenceDocument seq, ProjectState state) {
        if (state == null) return null;
        var simId = seq.activeSimConfigId();
        if (simId == null) return null;
        var sim = state.simulation(simId);
        if (sim == null || sim.config() == null) return null;
        ProjectNodeId circuitId = sim.config().circuitId();
        return circuitId == null ? null : state.circuit(circuitId);
    }
}
