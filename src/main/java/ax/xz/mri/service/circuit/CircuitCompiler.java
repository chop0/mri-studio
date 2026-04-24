package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.compile.CircuitStampContext;
import ax.xz.mri.model.circuit.compile.CtlBinding;
import ax.xz.mri.model.circuit.compile.Node;
import ax.xz.mri.model.circuit.compile.SwitchParams;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSource;
import ax.xz.mri.service.circuit.mna.MnaNetwork;
import ax.xz.mri.service.circuit.mna.MnaNetwork.VBranchKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link CircuitDocument} into a {@link CompiledCircuit}.
 *
 * <p>The compiler is <em>component-agnostic</em>: it does not switch on
 * concrete {@link CircuitComponent} subtypes. Each component owns its own
 * {@link CircuitComponent#stamp stamping logic}; the compiler's job is to
 * run union-find over the wires, build a {@link CircuitStampContext} backed
 * by mutable MNA builder arrays, and call {@code c.stamp(ctx)} on every
 * component.
 *
 * <p>Adding a new component type means adding a record with its own
 * {@code stamp}. The compiler does not change.
 */
public final class CircuitCompiler {
    private static final double DEFAULT_SERIES_R_OHMS = 1.0;

    private CircuitCompiler() {}

    public static CompiledCircuit compile(CircuitDocument circuit, ProjectRepository repository,
                                          double[] rMm, double[] zMm) {
        if (circuit == null) throw new IllegalArgumentException("CircuitCompiler.compile: circuit is null");

        // --- Union-find over terminals → dense MNA node ids. ---
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
        Map<Integer, Integer> rootToNode = new HashMap<>();
        Map<ComponentTerminal, Integer> nodeOf = new HashMap<>();
        for (var entry : termIndex.entrySet()) {
            int root = find(parent, entry.getValue());
            int nodeId = rootToNode.computeIfAbsent(root, k -> rootToNode.size());
            nodeOf.put(entry.getKey(), nodeId);
        }
        int nodeCount = rootToNode.size();

        // --- Build a context bound to each component in turn. ---
        var ctx = new CompilerContext(circuit, repository, rMm, zMm, nodeOf, nodeCount);
        for (var component : circuit.components()) {
            ctx.bindOwner(component);
            component.stamp(ctx);
        }
        return ctx.finish();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stamp context — the SPI bound to the component currently stamping
    // ─────────────────────────────────────────────────────────────────────

    private static final class CompilerContext implements CircuitStampContext {
        private final CircuitDocument circuit;
        private final ProjectRepository repository;
        private final double[] rMm;
        private final double[] zMm;
        private final Map<ComponentTerminal, Integer> nodeOf;
        private final int nodeCount;

        // Output metadata.
        private final List<CompiledSource> sources = new ArrayList<>();
        private final List<CompiledCoil> coils = new ArrayList<>();
        private final List<CompiledProbe> probes = new ArrayList<>();
        private int channelCursor = 0;

        // MNA stamps.
        private final List<Integer> resistorA = new ArrayList<>();
        private final List<Integer> resistorB = new ArrayList<>();
        private final List<Double> resistorG = new ArrayList<>();
        private final List<Integer> capacitorA = new ArrayList<>();
        private final List<Integer> capacitorB = new ArrayList<>();
        private final List<Double> capacitorF = new ArrayList<>();
        private final List<Integer> switchA = new ArrayList<>();
        private final List<Integer> switchB = new ArrayList<>();
        private final List<Double> switchClosedOhms = new ArrayList<>();
        private final List<Double> switchOpenOhms = new ArrayList<>();
        private final List<Double> switchThreshold = new ArrayList<>();
        private final List<CtlBinding> switchCtl = new ArrayList<>();
        private final List<Boolean> switchInvert = new ArrayList<>();
        private final List<Integer> branchNodeA = new ArrayList<>();
        private final List<Integer> branchNodeB = new ArrayList<>();
        private final List<VBranchKind> branchKind = new ArrayList<>();
        private final List<Integer> branchRefIndex = new ArrayList<>();
        private final List<Double> branchR = new ArrayList<>();
        private final List<Double> branchL = new ArrayList<>();
        private final List<Integer> mixerInNode = new ArrayList<>();
        private final List<Integer> mixerOutBranch = new ArrayList<>();
        private final List<Double> mixerLoHz = new ArrayList<>();
        private final List<Integer> metadataSourceIndex = new ArrayList<>();
        private final List<Integer> metadataOutBranch = new ArrayList<>();
        private final List<MnaNetwork.MetadataMode> metadataMode = new ArrayList<>();

        private final List<Integer> sourceOutBranch = new ArrayList<>();
        private final List<Integer> coilBranch = new ArrayList<>();
        private final List<Integer> probeNode = new ArrayList<>();
        private final Map<ComponentId, Integer> sourceIndexById = new HashMap<>();

        private CircuitComponent owner;

        CompilerContext(CircuitDocument circuit, ProjectRepository repository,
                        double[] rMm, double[] zMm,
                        Map<ComponentTerminal, Integer> nodeOf, int nodeCount) {
            this.circuit = circuit;
            this.repository = repository;
            this.rMm = rMm;
            this.zMm = zMm;
            this.nodeOf = nodeOf;
            this.nodeCount = nodeCount;
        }

        void bindOwner(CircuitComponent c) { this.owner = c; }

        // ─── CircuitStampContext ───

        @Override
        public Node port(String portName) {
            Integer n = nodeOf.get(new ComponentTerminal(owner.id(), portName));
            return new Node(n == null ? Node.GROUND.index() : n);
        }

        @Override
        public Node ground() { return Node.GROUND; }

        @Override
        public CtlBinding resolveCtl(Node ctlPort) {
            if (ctlPort.isGround()) return new CtlBinding.AlwaysOpen();
            // 1. Direct source-out wiring: ctl reads the source's scheduled voltage.
            for (var other : circuit.components()) {
                if (!(other instanceof CircuitComponent.VoltageSource src)) continue;
                Integer outNode = nodeOf.get(new ComponentTerminal(src.id(), "out"));
                int srcIndex = predictSourceIndex(src.id());
                if (outNode != null && outNode == ctlPort.index()) return new CtlBinding.FromSourceOut(srcIndex);
            }
            // 2. VoltageMetadata blocks: their "out" stamps a 0/1 flag.
            //    Resolve to FromSourceActive(srcIdx) so the switch's hot path
            //    stays a direct control-vector read rather than a node-voltage read.
            for (var other : circuit.components()) {
                if (!(other instanceof CircuitComponent.VoltageMetadata meta)) continue;
                Integer metaOutNode = nodeOf.get(new ComponentTerminal(meta.id(), "out"));
                if (metaOutNode == null || metaOutNode != ctlPort.index()) continue;
                var srcIdx = sourceIndexForName(meta.sourceName());
                if (srcIdx != null) return new CtlBinding.FromSourceActive(srcIdx);
            }
            return new CtlBinding.AlwaysOpen();
        }

        private int predictSourceIndex(ComponentId id) {
            int seen = 0;
            for (var c : circuit.components()) {
                if (c instanceof CircuitComponent.VoltageSource v) {
                    if (v.id().equals(id)) return seen;
                    seen++;
                }
            }
            throw new IllegalStateException("Source '" + id.value() + "' not in document");
        }

        @Override
        public void stampResistor(Node a, Node b, double ohms) {
            if (!(ohms > 0)) throw new IllegalArgumentException("stampResistor: ohms must be > 0, got " + ohms);
            resistorA.add(a.index());
            resistorB.add(b.index());
            resistorG.add(1.0 / ohms);
        }

        @Override
        public void stampCapacitor(Node a, Node b, double farads) {
            if (!(farads > 0)) throw new IllegalArgumentException("stampCapacitor: F must be > 0, got " + farads);
            capacitorA.add(a.index());
            capacitorB.add(b.index());
            capacitorF.add(farads);
        }

        @Override
        public void stampInductor(Node a, Node b, double henry) {
            if (!(henry > 0)) throw new IllegalArgumentException("stampInductor: H must be > 0, got " + henry);
            addBranch(a.index(), b.index(), VBranchKind.PASSIVE_INDUCTOR, -1, 0, henry);
        }

        @Override
        public void stampSwitch(Node a, Node b, SwitchParams params) {
            switchA.add(a.index());
            switchB.add(b.index());
            switchClosedOhms.add(params.closedOhms());
            switchOpenOhms.add(params.openOhms());
            switchThreshold.add(params.thresholdVolts());
            switchCtl.add(params.ctl());
            switchInvert.add(params.invert());
        }

        @Override
        public void stampMixer(Node in, Node out, double loHz) {
            if (in.isGround() || out.isGround()) {
                // Grounded in or out is non-sensical — skip stamp, treat as no-op.
                return;
            }
            int mixerIndex = mixerInNode.size();
            int branch = addBranch(out.index(), Node.GROUND.index(),
                VBranchKind.MIXER_OUT, mixerIndex, 0, 0);
            mixerInNode.add(in.index());
            mixerOutBranch.add(branch);
            mixerLoHz.add(loHz);
        }

        @Override
        public void registerSource(ComponentId id, String name, AmplitudeKind kind,
                                   double carrierHz, double staticAmplitude,
                                   Node outPort) {
            int index = sources.size();
            sourceIndexById.put(id, index);
            sources.add(new CompiledSource(id, name, channelCursor, kind, carrierHz, staticAmplitude));
            channelCursor += kind.channelCount();
            sourceOutBranch.add(addBranch(outPort.index(), Node.GROUND.index(),
                VBranchKind.SOURCE_OUT, index, 0, 0));
        }

        @Override
        public void stampVoltageMetadata(String sourceName, Node outPort,
                                         CircuitComponent.VoltageMetadata.Mode mode) {
            if (outPort.isGround()) return;
            Integer srcIdx = sourceIndexForName(sourceName);
            int m = metadataSourceIndex.size();
            metadataSourceIndex.add(srcIdx == null ? -1 : srcIdx);
            metadataMode.add(toNetworkMode(mode));
            int branch = addBranch(outPort.index(), Node.GROUND.index(),
                VBranchKind.METADATA_OUT, m, 0, 0);
            metadataOutBranch.add(branch);
        }

        private Integer sourceIndexForName(String sourceName) {
            if (sourceName == null || sourceName.isBlank()) return null;
            int seen = 0;
            for (var c : circuit.components()) {
                if (!(c instanceof CircuitComponent.VoltageSource src)) continue;
                if (src.name().equals(sourceName)) return seen;
                seen++;
            }
            return null;
        }

        private static MnaNetwork.MetadataMode toNetworkMode(CircuitComponent.VoltageMetadata.Mode mode) {
            return switch (mode) {
                case ACTIVE -> MnaNetwork.MetadataMode.ACTIVE;
            };
        }

        @Override
        public void registerCoil(ComponentId id, String name, ProjectNodeId eigenfieldId,
                                 double selfInductanceHenry, double seriesResistanceOhms,
                                 Node inPort) {
            double r = seriesResistanceOhms;
            double l = selfInductanceHenry;
            if (r == 0 && l == 0) r = DEFAULT_SERIES_R_OHMS;
            var sample = sampleEigenfield(eigenfieldId, repository, rMm, zMm);
            int index = coils.size();
            coils.add(new CompiledCoil(id, name, selfInductanceHenry, seriesResistanceOhms,
                sample[0], sample[1], sample[2]));
            coilBranch.add(addBranch(inPort.index(), Node.GROUND.index(),
                VBranchKind.COIL, index, r, l));
        }

        @Override
        public void registerProbe(ComponentId id, String name, double gain,
                                  double demodPhaseDeg, double loadImpedanceOhms, Node inPort) {
            probes.add(new CompiledProbe(id, name, gain, demodPhaseDeg, loadImpedanceOhms));
            probeNode.add(inPort.index());
        }

        // ─── Finalisation ───

        CompiledCircuit finish() {
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
                toIntArray(sourceOutBranch),
                toIntArray(coilBranch), toIntArray(probeNode),
                toIntArray(mixerInNode), toIntArray(mixerOutBranch), toDoubleArray(mixerLoHz),
                toIntArray(metadataSourceIndex), toIntArray(metadataOutBranch),
                metadataMode.toArray(new MnaNetwork.MetadataMode[0]));
            return new CompiledCircuit(sources, coils, probes, network, channelCursor);
        }

        private int addBranch(int nodeA, int nodeB, VBranchKind kind, int refIdx, double r, double l) {
            int idx = branchKind.size();
            branchNodeA.add(nodeA);
            branchNodeB.add(nodeB);
            branchKind.add(kind);
            branchRefIndex.add(refIdx);
            branchR.add(r);
            branchL.add(l);
            return idx;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Primitive helpers
    // ─────────────────────────────────────────────────────────────────────

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

    private static double[][][] sampleEigenfield(ProjectNodeId eigenfieldId,
                                                 ProjectRepository repository,
                                                 double[] rMm, double[] zMm) {
        int nR = rMm.length;
        int nZ = zMm.length;
        double[][] ex = new double[nR][nZ];
        double[][] ey = new double[nR][nZ];
        double[][] ez = new double[nR][nZ];

        EigenfieldDocument doc = (repository == null || eigenfieldId == null)
            ? null
            : (repository.node(eigenfieldId) instanceof EigenfieldDocument d ? d : null);
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
