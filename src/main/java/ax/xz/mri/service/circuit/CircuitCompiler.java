package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSource;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSwitch;
import ax.xz.mri.service.circuit.mna.MnaNetwork;
import ax.xz.mri.service.circuit.mna.MnaNetwork.CtlBinding;
import ax.xz.mri.service.circuit.mna.MnaNetwork.VBranchKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link CircuitDocument} into a {@link CompiledCircuit} the
 * simulator can run against.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Union-find over wires collapses every {@link ComponentTerminal} onto
 *       an integer node id. Ground is represented by {@code -1}.</li>
 *   <li>Each component is visited once and stamped into parallel arrays that
 *       the {@link ax.xz.mri.service.circuit.mna.MnaSolver} reads every
 *       timestep — no walker, no pseudo-switch pairs, no topology links.</li>
 *   <li>A switch's {@code ctl} port resolves to a {@link CtlBinding} — either
 *       a source's {@code out}/{@code active} port or {@code AlwaysOpen} for
 *       floating ctl. More exotic bindings (ctl driven by a node voltage in
 *       the rest of the network) are rejected; that requires a nonlinear
 *       solve and isn't in scope here.</li>
 * </ol>
 *
 * <p>Coils default to a 1 Ω series resistance if the user leaves both {@code
 * seriesResistanceOhms} and {@code selfInductanceHenry} at zero. That keeps
 * the historical {@code V_source == I_coil} numerical convention working —
 * source voltage feeds directly into the coil's drive current, and the
 * eigenfield's {@code defaultMagnitude} (B per unit current) converts that
 * into a real B field. Non-zero user values are honoured unchanged.
 */
public final class CircuitCompiler {
    private static final double DEFAULT_SERIES_R_OHMS = 1.0;

    private CircuitCompiler() {}

    public static CompiledCircuit compile(CircuitDocument circuit, ProjectRepository repository,
                                          double[] rMm, double[] zMm) {
        if (circuit == null) throw new IllegalArgumentException("CircuitCompiler.compile: circuit is null");

        // --- Union-find over terminals to discover nodes. ---
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

        // Collapse terminal roots to dense node ids. Any terminal whose net
        // carries no component-side constraint other than a source return is
        // fine — the solver handles dangling nodes transparently. Ground is
        // -1; nodes are numbered from 0 up.
        Map<Integer, Integer> rootToNode = new HashMap<>();
        Map<ComponentTerminal, Integer> nodeOf = new HashMap<>();
        for (var entry : termIndex.entrySet()) {
            int root = find(parent, entry.getValue());
            int nodeId = rootToNode.computeIfAbsent(root, k -> rootToNode.size());
            nodeOf.put(entry.getKey(), nodeId);
        }
        int nodeCount = rootToNode.size();

        // --- Enumerate components and build the CompiledCircuit metadata. ---
        var sources = new ArrayList<CompiledSource>();
        var switches = new ArrayList<CompiledSwitch>();
        var coils = new ArrayList<CompiledCoil>();
        var probes = new ArrayList<CompiledProbe>();
        var sourceIndexById = new HashMap<ComponentId, Integer>();
        var coilIndexById = new HashMap<ComponentId, Integer>();

        int channelCursor = 0;
        for (var component : circuit.components()) {
            switch (component) {
                case CircuitComponent.VoltageSource v -> {
                    sourceIndexById.put(v.id(), sources.size());
                    sources.add(new CompiledSource(
                        v.id(), v.name(), channelCursor,
                        v.kind(), v.carrierHz(), v.maxAmplitude()));
                    channelCursor += v.channelCount();
                }
                case CircuitComponent.Coil c -> {
                    coilIndexById.put(c.id(), coils.size());
                    var sample = sampleEigenfield(c, repository, rMm, zMm);
                    coils.add(new CompiledCoil(
                        c.id(), c.name(),
                        c.selfInductanceHenry(), c.seriesResistanceOhms(),
                        sample[0], sample[1], sample[2]));
                }
                case CircuitComponent.Probe p ->
                    probes.add(new CompiledProbe(
                        p.id(), p.name(),
                        p.gain(), p.carrierHz(), p.demodPhaseDeg(), p.loadImpedanceOhms()));
                case CircuitComponent.SwitchComponent s ->
                    switches.add(new CompiledSwitch(
                        s.id(), s.name(),
                        s.closedOhms(), s.openOhms(), s.thresholdVolts(), s.invertCtl()));
                default -> { /* passives and muxes contribute only to MNA stamps */ }
            }
        }

        // --- Build the MNA network. ---
        var resistorA = new ArrayList<Integer>();
        var resistorB = new ArrayList<Integer>();
        var resistorG = new ArrayList<Double>();
        var capacitorA = new ArrayList<Integer>();
        var capacitorB = new ArrayList<Integer>();
        var capacitorF = new ArrayList<Double>();
        var switchA = new ArrayList<Integer>();
        var switchB = new ArrayList<Integer>();
        var switchClosedOhms = new ArrayList<Double>();
        var switchOpenOhms = new ArrayList<Double>();
        var switchThreshold = new ArrayList<Double>();
        var switchCtl = new ArrayList<CtlBinding>();
        var switchInvert = new ArrayList<Boolean>();
        var branchNodeA = new ArrayList<Integer>();
        var branchNodeB = new ArrayList<Integer>();
        var branchKind = new ArrayList<VBranchKind>();
        var branchRefIndex = new ArrayList<Integer>();
        var branchR = new ArrayList<Double>();
        var branchL = new ArrayList<Double>();

        int[] sourceOutBranch = new int[sources.size()];
        int[] sourceActiveBranch = new int[sources.size()];
        int[] coilBranchIdx = new int[coils.size()];
        java.util.Arrays.fill(sourceOutBranch, -1);
        java.util.Arrays.fill(sourceActiveBranch, -1);

        // Each source contributes two voltage branches (out and active). Both
        // return through implicit ground — the user's schematic never wires
        // the "other side" of a source by convention.
        for (int s = 0; s < sources.size(); s++) {
            var src = sources.get(s);
            sourceOutBranch[s] = addBranch(branchNodeA, branchNodeB, branchKind, branchRefIndex, branchR, branchL,
                nodeOrGround(nodeOf, new ComponentTerminal(src.id(), "out")), -1,
                VBranchKind.SOURCE_OUT, s, 0, 0);
            sourceActiveBranch[s] = addBranch(branchNodeA, branchNodeB, branchKind, branchRefIndex, branchR, branchL,
                nodeOrGround(nodeOf, new ComponentTerminal(src.id(), "active")), -1,
                VBranchKind.SOURCE_ACTIVE, s, 0, 0);
        }

        // Coils: one branch each, between coil.in and ground. A coil with no
        // user-supplied R or L falls back to R = 1 Ω so the source-voltage-
        // equals-coil-current convention from the walker era still holds.
        for (int c = 0; c < coils.size(); c++) {
            var coil = coils.get(c);
            double r = coil.seriesResistanceOhms();
            double l = coil.selfInductanceHenry();
            if (r == 0 && l == 0) r = DEFAULT_SERIES_R_OHMS;
            coilBranchIdx[c] = addBranch(branchNodeA, branchNodeB, branchKind, branchRefIndex, branchR, branchL,
                nodeOrGround(nodeOf, new ComponentTerminal(coil.id(), "in")), -1,
                VBranchKind.COIL, c, r, l);
        }

        // Passives (series + shunt) and switches (+ mux-expanded).
        for (var component : circuit.components()) {
            switch (component) {
                case CircuitComponent.Resistor r -> addResistor(resistorA, resistorB, resistorG,
                    nodeOrGround(nodeOf, new ComponentTerminal(r.id(), "a")),
                    nodeOrGround(nodeOf, new ComponentTerminal(r.id(), "b")),
                    r.resistanceOhms());
                case CircuitComponent.Capacitor c -> addCapacitor(capacitorA, capacitorB, capacitorF,
                    nodeOrGround(nodeOf, new ComponentTerminal(c.id(), "a")),
                    nodeOrGround(nodeOf, new ComponentTerminal(c.id(), "b")),
                    c.capacitanceFarads());
                case CircuitComponent.Inductor l -> addBranch(branchNodeA, branchNodeB, branchKind, branchRefIndex, branchR, branchL,
                    nodeOrGround(nodeOf, new ComponentTerminal(l.id(), "a")),
                    nodeOrGround(nodeOf, new ComponentTerminal(l.id(), "b")),
                    VBranchKind.PASSIVE_INDUCTOR, -1, 0, l.inductanceHenry());
                case CircuitComponent.ShuntResistor r -> addResistor(resistorA, resistorB, resistorG,
                    nodeOrGround(nodeOf, new ComponentTerminal(r.id(), "in")),
                    -1,
                    r.resistanceOhms());
                case CircuitComponent.ShuntCapacitor c -> addCapacitor(capacitorA, capacitorB, capacitorF,
                    nodeOrGround(nodeOf, new ComponentTerminal(c.id(), "in")),
                    -1,
                    c.capacitanceFarads());
                case CircuitComponent.ShuntInductor l -> addBranch(branchNodeA, branchNodeB, branchKind, branchRefIndex, branchR, branchL,
                    nodeOrGround(nodeOf, new ComponentTerminal(l.id(), "in")),
                    -1,
                    VBranchKind.PASSIVE_INDUCTOR, -1, 0, l.inductanceHenry());
                case CircuitComponent.SwitchComponent s -> {
                    switchA.add(nodeOrGround(nodeOf, new ComponentTerminal(s.id(), "a")));
                    switchB.add(nodeOrGround(nodeOf, new ComponentTerminal(s.id(), "b")));
                    switchClosedOhms.add(s.closedOhms());
                    switchOpenOhms.add(s.openOhms());
                    switchThreshold.add(s.thresholdVolts());
                    switchCtl.add(resolveCtl(circuit, nodeOf, sourceIndexById,
                        new ComponentTerminal(s.id(), "ctl")));
                    switchInvert.add(s.invertCtl());
                }
                case CircuitComponent.Multiplexer m -> {
                    int aNode = nodeOrGround(nodeOf, new ComponentTerminal(m.id(), "a"));
                    int bNode = nodeOrGround(nodeOf, new ComponentTerminal(m.id(), "b"));
                    int commonNode = nodeOrGround(nodeOf, new ComponentTerminal(m.id(), "common"));
                    var ctl = resolveCtl(circuit, nodeOf, sourceIndexById,
                        new ComponentTerminal(m.id(), "ctl"));
                    // a ↔ common closes when ctl is high (non-inverting).
                    switchA.add(aNode);
                    switchB.add(commonNode);
                    switchClosedOhms.add(m.closedOhms());
                    switchOpenOhms.add(m.openOhms());
                    switchThreshold.add(m.thresholdVolts());
                    switchCtl.add(ctl);
                    switchInvert.add(false);
                    // b ↔ common closes when ctl is low (inverting).
                    switchA.add(bNode);
                    switchB.add(commonNode);
                    switchClosedOhms.add(m.closedOhms());
                    switchOpenOhms.add(m.openOhms());
                    switchThreshold.add(m.thresholdVolts());
                    switchCtl.add(ctl);
                    switchInvert.add(true);
                }
                default -> { /* already handled above, or nothing to stamp */ }
            }
        }

        int[] probeNode = new int[probes.size()];
        for (int p = 0; p < probes.size(); p++) {
            probeNode[p] = nodeOrGround(nodeOf, new ComponentTerminal(probes.get(p).id(), "in"));
        }

        var network = new MnaNetwork(
            nodeCount,
            branchKind.size(),
            toIntArray(resistorA), toIntArray(resistorB), toDoubleArray(resistorG),
            toIntArray(capacitorA), toIntArray(capacitorB), toDoubleArray(capacitorF),
            toIntArray(switchA), toIntArray(switchB),
            toDoubleArray(switchClosedOhms), toDoubleArray(switchOpenOhms), toDoubleArray(switchThreshold),
            switchCtl.toArray(new CtlBinding[0]), toBooleanArray(switchInvert),
            toIntArray(branchNodeA), toIntArray(branchNodeB),
            branchKind.toArray(new VBranchKind[0]), toIntArray(branchRefIndex),
            toDoubleArray(branchR), toDoubleArray(branchL),
            sourceOutBranch, sourceActiveBranch, coilBranchIdx, probeNode);

        return new CompiledCircuit(sources, switches, coils, probes, network, channelCursor);
    }

    // ───────── Helpers ─────────

    private static int nodeOrGround(Map<ComponentTerminal, Integer> nodeOf, ComponentTerminal t) {
        Integer n = nodeOf.get(t);
        return n == null ? -1 : n;
    }

    private static void addResistor(List<Integer> as, List<Integer> bs, List<Double> gs,
                                     int a, int b, double ohms) {
        as.add(a);
        bs.add(b);
        gs.add(1.0 / ohms);
    }

    private static void addCapacitor(List<Integer> as, List<Integer> bs, List<Double> fs,
                                      int a, int b, double farads) {
        as.add(a);
        bs.add(b);
        fs.add(farads);
    }

    private static int addBranch(List<Integer> branchNodeA, List<Integer> branchNodeB,
                                  List<VBranchKind> kind, List<Integer> ref,
                                  List<Double> branchR, List<Double> branchL,
                                  int nodeA, int nodeB,
                                  VBranchKind k, int refIdx, double r, double l) {
        int idx = kind.size();
        branchNodeA.add(nodeA);
        branchNodeB.add(nodeB);
        kind.add(k);
        ref.add(refIdx);
        branchR.add(r);
        branchL.add(l);
        return idx;
    }

    private static CtlBinding resolveCtl(CircuitDocument circuit,
                                          Map<ComponentTerminal, Integer> nodeOf,
                                          Map<ComponentId, Integer> sourceIndexById,
                                          ComponentTerminal ctl) {
        Integer ctlNode = nodeOf.get(ctl);
        if (ctlNode == null) return new CtlBinding.AlwaysOpen();
        for (var other : circuit.components()) {
            if (!(other instanceof CircuitComponent.VoltageSource src)) continue;
            Integer outNode = nodeOf.get(new ComponentTerminal(src.id(), "out"));
            Integer activeNode = nodeOf.get(new ComponentTerminal(src.id(), "active"));
            int srcIdx = sourceIndexById.get(src.id());
            if (outNode != null && outNode.equals(ctlNode))
                return new CtlBinding.FromSourceOut(srcIdx);
            if (activeNode != null && activeNode.equals(ctlNode))
                return new CtlBinding.FromSourceActive(srcIdx);
        }
        return new CtlBinding.AlwaysOpen();
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

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private static boolean[] toBooleanArray(List<Boolean> list) {
        boolean[] out = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
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
