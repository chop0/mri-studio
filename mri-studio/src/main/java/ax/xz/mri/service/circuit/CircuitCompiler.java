package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledPassive;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSource;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSwitch;
import ax.xz.mri.service.circuit.CompiledCircuit.TopologyLink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link CircuitDocument} into a {@link CompiledCircuit} the
 * simulator can run against.
 *
 * <p>Three passes:
 * <ol>
 *   <li><b>Union-find</b> over wires groups each {@link ComponentTerminal}
 *       into an electrical node (an integer id).</li>
 *   <li><b>Component extraction</b> populates the sources / switches / coils
 *       / probes / passives lists and assigns sequence-control offsets in
 *       declaration order.</li>
 *   <li><b>Topology resolution</b> walks each source (resp. probe) through
 *       switches-on-the-path to the coil it ultimately drives (resp.
 *       observes). The pre-computed {@link TopologyLink}s drive the
 *       per-step simulator so it never re-walks the graph.</li>
 * </ol>
 */
public final class CircuitCompiler {
    private CircuitCompiler() {}

    public static CompiledCircuit compile(CircuitDocument circuit, ProjectRepository repository,
                                          double[] rMm, double[] zMm) {
        if (circuit == null) throw new IllegalArgumentException("CircuitCompiler.compile: circuit is null");

        // Union-find over all terminals in the circuit.
        var termIndex = new HashMap<ComponentTerminal, Integer>();
        for (var c : circuit.components()) {
            for (var port : c.ports()) {
                termIndex.put(new ComponentTerminal(c.id(), port), termIndex.size());
            }
        }
        int[] parent = new int[termIndex.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (var w : circuit.wires()) {
            union(parent, termIndex.get(w.from()), termIndex.get(w.to()));
        }
        Map<ComponentTerminal, Integer> nodeOf = new HashMap<>();
        for (var entry : termIndex.entrySet()) nodeOf.put(entry.getKey(), find(parent, entry.getValue()));

        // Pass 2: enumerate each component kind and wire up channel offsets.
        var sources = new ArrayList<CompiledSource>();
        var switches = new ArrayList<CompiledSwitch>();
        var coils = new ArrayList<CompiledCoil>();
        var probes = new ArrayList<CompiledProbe>();
        var resistors = new ArrayList<CompiledPassive>();
        var capacitors = new ArrayList<CompiledPassive>();
        var inductors = new ArrayList<CompiledPassive>();

        var sourceIndexById = new HashMap<ComponentId, Integer>();
        var coilIndexById = new HashMap<ComponentId, Integer>();
        var switchIndexById = new HashMap<ComponentId, Integer>();
        var probeIndexById = new HashMap<ComponentId, Integer>();

        int channelCursor = 0;
        for (var component : circuit.components()) {
            switch (component) {
                case CircuitComponent.VoltageSource v -> {
                    sourceIndexById.put(v.id(), sources.size());
                    sources.add(new CompiledSource(
                        v.id(), v.name(),
                        channelCursor, v.kind(), v.carrierHz(), v.maxAmplitude()));
                    channelCursor += v.channelCount();
                }
                case CircuitComponent.SwitchComponent s -> {
                    switchIndexById.put(s.id(), switches.size());
                    // ctlSourceIndex filled in during topology pass
                    switches.add(new CompiledSwitch(
                        s.id(), s.name(), -1,
                        s.closedOhms(), s.openOhms(), s.thresholdVolts()));
                }
                case CircuitComponent.Coil c -> {
                    coilIndexById.put(c.id(), coils.size());
                    var sample = sampleEigenfield(c, repository, rMm, zMm);
                    coils.add(new CompiledCoil(
                        c.id(), c.name(),
                        c.selfInductanceHenry(), c.seriesResistanceOhms(),
                        sample[0], sample[1], sample[2]));
                }
                case CircuitComponent.Probe p -> {
                    probeIndexById.put(p.id(), probes.size());
                    probes.add(new CompiledProbe(
                        p.id(), p.name(),
                        p.gain(), p.demodPhaseDeg(), p.loadImpedanceOhms()));
                }
                case CircuitComponent.Resistor r ->
                    resistors.add(new CompiledPassive(r.id(), r.name(), r.resistanceOhms()));
                case CircuitComponent.Capacitor c ->
                    capacitors.add(new CompiledPassive(c.id(), c.name(), c.capacitanceFarads()));
                case CircuitComponent.Inductor l ->
                    inductors.add(new CompiledPassive(l.id(), l.name(), l.inductanceHenry()));
                case CircuitComponent.Ground g -> { /* reference only */ }
                case CircuitComponent.IdealTransformer t ->
                    throw new UnsupportedOperationException("IdealTransformer not yet supported");
            }
        }

        // Ground net: every ground component's "a" terminal. Anything on this
        // net is treated as the common return.
        int groundNode = findGroundNode(circuit, nodeOf);

        // Pass 3: resolve switch ctl → source, and walk source↔coil↔probe paths.
        for (var component : circuit.components()) {
            if (!(component instanceof CircuitComponent.SwitchComponent s)) continue;
            int ctlSourceIdx = findSourceDrivingTerminal(
                circuit, nodeOf, sourceIndexById,
                new ComponentTerminal(s.id(), "ctl"));
            int idx = switchIndexById.get(s.id());
            var existing = switches.get(idx);
            switches.set(idx, new CompiledSwitch(
                existing.id(), existing.name(), ctlSourceIdx,
                existing.closedOhms(), existing.openOhms(), existing.thresholdVolts()));
        }

        // Resolve drives: each non-gate source's "out" terminal reaches a coil
        // (possibly through switches); the coil's other terminal must sit on
        // the ground net.
        var drives = new ArrayList<TopologyLink>();
        for (int srcIdx = 0; srcIdx < sources.size(); srcIdx++) {
            var src = sources.get(srcIdx);
            if (src.kind() == ax.xz.mri.model.simulation.AmplitudeKind.GATE) continue;
            var link = walkSingleToCoil(circuit, nodeOf, coilIndexById, switchIndexById,
                new ComponentTerminal(src.id(), "out"), groundNode, srcIdx);
            if (link != null) drives.add(link);
        }

        // Resolve observes: probe's "in" reaches a coil, other terminal on ground.
        var observes = new ArrayList<TopologyLink>();
        for (int probeIdx = 0; probeIdx < probes.size(); probeIdx++) {
            var probe = probes.get(probeIdx);
            var link = walkSingleToCoil(circuit, nodeOf, coilIndexById, switchIndexById,
                new ComponentTerminal(probe.id(), "in"), groundNode, probeIdx);
            if (link != null) observes.add(link);
        }

        return new CompiledCircuit(sources, switches, coils, probes, drives, observes,
            resistors, capacitors, inductors, channelCursor);
    }

    /** Find the source whose {@code out} terminal sits on the same net as {@code terminal}, or -1. */
    private static int findSourceDrivingTerminal(
        CircuitDocument circuit, Map<ComponentTerminal, Integer> nodeOf,
        Map<ComponentId, Integer> sourceIndexById, ComponentTerminal terminal
    ) {
        int node = nodeOf.get(terminal);
        for (var component : circuit.components()) {
            if (!(component instanceof CircuitComponent.VoltageSource src)) continue;
            int outNode = nodeOf.get(new ComponentTerminal(src.id(), "out"));
            if (outNode == node) return sourceIndexById.get(src.id());
        }
        return -1;
    }

    private static int findGroundNode(CircuitDocument circuit, Map<ComponentTerminal, Integer> nodeOf) {
        for (var c : circuit.components()) {
            if (c instanceof CircuitComponent.Ground) {
                return nodeOf.get(new ComponentTerminal(c.id(), "a"));
            }
        }
        return -1;
    }

    /**
     * Walk from a single-terminal endpoint (source.out or probe.in) to a coil,
     * passing through zero or more switches. The coil's far terminal must
     * land on the ground net — the implicit return for every source/probe.
     * Polarity is forward iff the walk entered the coil at {@code "a"}.
     */
    private static TopologyLink walkSingleToCoil(
        CircuitDocument circuit, Map<ComponentTerminal, Integer> nodeOf,
        Map<ComponentId, Integer> coilIndexById, Map<ComponentId, Integer> switchIndexById,
        ComponentTerminal outTerminal, int groundNode, int endpointIndex
    ) {
        if (groundNode < 0) return null;
        var switchIdsOnPath = new ArrayList<Integer>();
        var reached = walkNetToCoil(circuit, nodeOf, coilIndexById, switchIndexById,
            nodeOf.get(outTerminal), switchIdsOnPath, new HashSet<>(), null);
        if (reached == null) return null;
        // Other coil terminal must be in the ground net.
        String otherPort = reached.reachedPort.equals("a") ? "b" : "a";
        int otherNode = nodeOf.get(new ComponentTerminal(reached.coilId, otherPort));
        if (otherNode != groundNode) return null;
        int coilIndex = coilIndexById.get(reached.coilId);
        boolean forward = reached.reachedPort.equals("a");
        return new TopologyLink(endpointIndex, coilIndex, switchIdsOnPath, forward);
    }

    private record NetWalkResult(ComponentId coilId, String reachedPort) {}

    /**
     * BFS from a starting node through switches. When the net contains a coil
     * terminal, return it. When {@code requireCoilId} is non-null, only that
     * specific coil terminates the walk — other coils are skipped so the
     * shared-ground case resolves correctly.
     */
    private static NetWalkResult walkNetToCoil(
        CircuitDocument circuit, Map<ComponentTerminal, Integer> nodeOf,
        Map<ComponentId, Integer> coilIndexById, Map<ComponentId, Integer> switchIndexById,
        int startNode, List<Integer> outSwitchIds, java.util.Set<Integer> visited,
        ComponentId requireCoilId
    ) {
        if (!visited.add(startNode)) return null;
        for (var component : circuit.components()) {
            for (var port : component.ports()) {
                var term = new ComponentTerminal(component.id(), port);
                if (nodeOf.get(term) != startNode) continue;
                if (component instanceof CircuitComponent.Coil coil) {
                    if (requireCoilId != null && !coil.id().equals(requireCoilId)) continue;
                    return new NetWalkResult(component.id(), port);
                }
                if (component instanceof CircuitComponent.SwitchComponent sw) {
                    if (!port.equals("a") && !port.equals("b")) continue;
                    String other = port.equals("a") ? "b" : "a";
                    int otherNode = nodeOf.get(new ComponentTerminal(sw.id(), other));
                    int swIdx = switchIndexById.get(sw.id());
                    if (!outSwitchIds.contains(swIdx)) outSwitchIds.add(swIdx);
                    var deeper = walkNetToCoil(circuit, nodeOf, coilIndexById, switchIndexById,
                        otherNode, outSwitchIds, visited, requireCoilId);
                    if (deeper != null) return deeper;
                }
            }
        }
        return null;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    private static double[][][] sampleEigenfield(CircuitComponent.Coil coil, ProjectRepository repository,
                                                 double[] rMm, double[] zMm) {
        int nR = rMm.length;
        int nZ = zMm.length;
        double[][] ex = new double[nR][nZ];
        double[][] ey = new double[nR][nZ];
        double[][] ez = new double[nR][nZ];

        EigenfieldDocument doc = (repository == null || coil.eigenfieldId() == null)
            ? null
            : (repository.node(coil.eigenfieldId()) instanceof EigenfieldDocument d ? d : null);
        if (doc == null) return new double[][][]{ex, ey, ez};

        EigenfieldScript script;
        try {
            script = EigenfieldScriptEngine.compile(doc.script());
        } catch (RuntimeException compileFailure) {
            return new double[][][]{ex, ey, ez};
        }
        double scale = doc.defaultMagnitude();

        for (int ri = 0; ri < nR; ri++) {
            double x = rMm[ri] * 1e-3;
            for (int zi = 0; zi < nZ; zi++) {
                double z = zMm[zi] * 1e-3;
                Vec3 v;
                try { v = script.evaluate(x, 0, z); } catch (Throwable t) { v = Vec3.ZERO; }
                if (v == null) v = Vec3.ZERO;
                ex[ri][zi] = scale * (Double.isFinite(v.x()) ? v.x() : 0);
                ey[ri][zi] = scale * (Double.isFinite(v.y()) ? v.y() : 0);
                ez[ri][zi] = scale * (Double.isFinite(v.z()) ? v.z() : 0);
            }
        }
        return new double[][][]{ex, ey, ez};
    }
}
