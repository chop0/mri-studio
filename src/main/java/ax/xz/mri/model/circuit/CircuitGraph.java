package ax.xz.mri.model.circuit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reusable graph helpers over the wire+component graph of a {@link CircuitDocument}. */
public final class CircuitGraph {
    private CircuitGraph() {}

    /** Wire adjacency: every terminal mapped to the terminals it's wired to. */
    public static Map<ComponentTerminal, List<ComponentTerminal>> wireAdjacency(CircuitDocument doc) {
        var map = new HashMap<ComponentTerminal, List<ComponentTerminal>>();
        for (var w : doc.wires()) {
            map.computeIfAbsent(w.from(), k -> new ArrayList<>()).add(w.to());
            map.computeIfAbsent(w.to(), k -> new ArrayList<>()).add(w.from());
        }
        return map;
    }

    /** Index components by id. */
    public static Map<ComponentId, CircuitComponent> componentMap(CircuitDocument doc) {
        var map = new HashMap<ComponentId, CircuitComponent>();
        for (var c : doc.components()) map.put(c.id(), c);
        return map;
    }

    /**
     * The electrical net containing {@code seed}: every terminal reachable
     * from the seed by walking wires only (no internal pass-throughs).
     */
    public static Set<ComponentTerminal> netOf(CircuitDocument doc, ComponentTerminal seed) {
        var net = new HashSet<ComponentTerminal>();
        net.add(seed);
        boolean grew;
        do {
            grew = false;
            for (var w : doc.wires()) {
                if (net.contains(w.from()) && net.add(w.to())) grew = true;
                if (net.contains(w.to()) && net.add(w.from())) grew = true;
            }
        } while (grew);
        return net;
    }

    /**
     * BFS from {@code sourceId}'s {@code "a"}/{@code "b"} terminals (i.e. a
     * voltage source's hot and cold sides) through wires and through the
     * pass-through behaviour of switches, looking for the first reachable
     * coil that isn't the source itself.
     */
    public static Optional<CircuitComponent.Coil> coilReachableFrom(CircuitDocument doc, ComponentId sourceId) {
        var adjacency = wireAdjacency(doc);
        var componentsById = componentMap(doc);
        var visited = new HashSet<ComponentTerminal>();
        var queue = new ArrayDeque<ComponentTerminal>();
        queue.add(new ComponentTerminal(sourceId, "a"));
        queue.add(new ComponentTerminal(sourceId, "b"));
        while (!queue.isEmpty()) {
            var term = queue.poll();
            if (!visited.add(term)) continue;
            var component = componentsById.get(term.componentId());
            if (component instanceof CircuitComponent.Coil coil && !term.componentId().equals(sourceId)) {
                return Optional.of(coil);
            }
            if (component instanceof CircuitComponent.SwitchComponent && (term.port().equals("a") || term.port().equals("b"))) {
                queue.add(new ComponentTerminal(term.componentId(), term.port().equals("a") ? "b" : "a"));
            }
            for (var neighbour : adjacency.getOrDefault(term, List.of())) queue.add(neighbour);
        }
        return Optional.empty();
    }
}
