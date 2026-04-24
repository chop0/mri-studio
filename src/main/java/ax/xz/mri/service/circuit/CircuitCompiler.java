package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.compile.CircuitStampContext;
import ax.xz.mri.model.circuit.compile.ComplexPairFormat;
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
        private final List<Integer> mixerOut0Branch = new ArrayList<>();
        private final List<Integer> mixerOut1Branch = new ArrayList<>();
        private final List<Double> mixerLoHz = new ArrayList<>();
        private final List<ComplexPairFormat> mixerFormat = new ArrayList<>();
        private final List<int[]> metadataSourceIndices = new ArrayList<>();
        private final List<Integer> metadataOutBranch = new ArrayList<>();
        private final List<MnaNetwork.MetadataMode> metadataMode = new ArrayList<>();
        private final List<Integer> modulatorIn0Node = new ArrayList<>();
        private final List<Integer> modulatorIn1Node = new ArrayList<>();
        private final List<Integer> modulatorOutBranch = new ArrayList<>();
        private final List<Double> modulatorLoHz = new ArrayList<>();
        private final List<ComplexPairFormat> modulatorFormat = new ArrayList<>();

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
            //    Resolve to FromSourceActive(srcIndices) so the switch's
            //    hot path stays a direct control-vector read rather than a
            //    node-voltage read. A tap pointing at a Modulator expands
            //    to both its I and Q sources; the OR means the switch
            //    fires when either envelope plays.
            for (var other : circuit.components()) {
                if (!(other instanceof CircuitComponent.VoltageMetadata meta)) continue;
                Integer metaOutNode = nodeOf.get(new ComponentTerminal(meta.id(), "out"));
                if (metaOutNode == null || metaOutNode != ctlPort.index()) continue;
                int[] indices = sourceIndicesForMetadataName(meta.sourceName());
                if (indices.length > 0) return new CtlBinding.FromSourceActive(indices);
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
        public void stampMixer(Node in, Node out0, Node out1, double loHz,
                               ComplexPairFormat format) {
            // If every terminal is grounded the mixer is a no-op; that's fine.
            if (in.isGround() && out0.isGround() && out1.isGround()) return;
            int mixerIndex = mixerInNode.size();
            int b0 = out0.isGround() ? -1 :
                addBranch(out0.index(), Node.GROUND.index(),
                    VBranchKind.MIXER_OUT_0, mixerIndex, 0, 0);
            int b1 = out1.isGround() ? -1 :
                addBranch(out1.index(), Node.GROUND.index(),
                    VBranchKind.MIXER_OUT_1, mixerIndex, 0, 0);
            mixerInNode.add(in.index());
            mixerOut0Branch.add(b0);
            mixerOut1Branch.add(b1);
            mixerLoHz.add(loHz);
            mixerFormat.add(format);
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
            int[] indices = sourceIndicesForMetadataName(sourceName);
            int m = metadataSourceIndices.size();
            metadataSourceIndices.add(indices);
            metadataMode.add(toNetworkMode(mode));
            int branch = addBranch(outPort.index(), Node.GROUND.index(),
                VBranchKind.METADATA_OUT, m, 0, 0);
            metadataOutBranch.add(branch);
        }

        /**
         * Resolve a metadata tap's {@code sourceName} to the set of compiled
         * source indices it should observe. A match against a
         * {@link CircuitComponent.VoltageSource} is a single-element set;
         * a match against a {@link CircuitComponent.Modulator} expands to
         * both the modulator's I and Q sources so the tap fires on either
         * envelope playing.
         */
        private int[] sourceIndicesForMetadataName(String name) {
            if (name == null || name.isBlank()) return new int[0];
            for (var c : circuit.components()) {
                if (c instanceof CircuitComponent.Modulator m && name.equals(m.name())) {
                    var indices = new ArrayList<Integer>();
                    var iSrc = CircuitComponent.Modulator.inputSource(m, "in0", circuit);
                    var qSrc = CircuitComponent.Modulator.inputSource(m, "in1", circuit);
                    if (iSrc != null) {
                        Integer iIdx = sourceIndexForName(iSrc.name());
                        if (iIdx != null) indices.add(iIdx);
                    }
                    if (qSrc != null) {
                        Integer qIdx = sourceIndexForName(qSrc.name());
                        if (qIdx != null) indices.add(qIdx);
                    }
                    int[] out = new int[indices.size()];
                    for (int i = 0; i < out.length; i++) out[i] = indices.get(i);
                    return out;
                }
            }
            Integer srcIdx = sourceIndexForName(name);
            return srcIdx == null ? new int[0] : new int[]{srcIdx};
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

        @Override
        public void stampModulator(Node in0, Node in1, Node out,
                                   double loHz, ComplexPairFormat format) {
            if (out.isGround()) return;
            int m = modulatorOutBranch.size();
            modulatorIn0Node.add(in0.index());
            modulatorIn1Node.add(in1.index());
            modulatorLoHz.add(loHz);
            modulatorFormat.add(format);
            int branch = addBranch(out.index(), Node.GROUND.index(),
                VBranchKind.MODULATOR_OUT, m, 0, 0);
            modulatorOutBranch.add(branch);
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
            // Collect the control-vector offsets of every source whose "out"
            // node ended up on the same MNA node as a Modulator's in0 or in1.
            // Those are the "RF envelope" channels downstream optimiser-side
            // penalties (RF power, smoothness) weight. Wiring drives the
            // relationship now — no hidden name lookups.
            var rfNodes = new java.util.HashSet<Integer>();
            for (int m = 0; m < modulatorIn0Node.size(); m++) {
                int n0 = modulatorIn0Node.get(m);
                int n1 = modulatorIn1Node.get(m);
                if (n0 >= 0) rfNodes.add(n0);
                if (n1 >= 0) rfNodes.add(n1);
            }
            var rfOffsetList = new ArrayList<Integer>();
            for (var comp : circuit.components()) {
                if (!(comp instanceof CircuitComponent.VoltageSource v)) continue;
                Integer outNode = nodeOf.get(new ComponentTerminal(v.id(), "out"));
                if (outNode == null || !rfNodes.contains(outNode)) continue;
                Integer srcIdx = sourceIndexById.get(v.id());
                if (srcIdx == null) continue;
                var compiled = sources.get(srcIdx);
                for (int k = 0; k < compiled.kind().channelCount(); k++) {
                    rfOffsetList.add(compiled.channelOffset() + k);
                }
            }
            int[] rfOffsets = toIntArray(rfOffsetList);

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
                toIntArray(mixerInNode), toIntArray(mixerOut0Branch), toIntArray(mixerOut1Branch),
                toDoubleArray(mixerLoHz),
                mixerFormat.toArray(new ComplexPairFormat[0]),
                metadataSourceIndices.toArray(new int[0][]),
                toIntArray(metadataOutBranch),
                metadataMode.toArray(new MnaNetwork.MetadataMode[0]),
                toIntArray(modulatorIn0Node), toIntArray(modulatorIn1Node),
                toIntArray(modulatorOutBranch), toDoubleArray(modulatorLoHz),
                modulatorFormat.toArray(new ComplexPairFormat[0]));
            return new CompiledCircuit(sources, coils, probes, network, channelCursor, rfOffsets);
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
