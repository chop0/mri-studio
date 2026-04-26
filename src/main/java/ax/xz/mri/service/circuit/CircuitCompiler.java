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
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link CircuitDocument} into a {@link CompiledCircuit}.
 *
 * <p>The compiler is <em>component-agnostic</em>: it does not switch on
 * concrete {@link CircuitComponent} subtypes. Each component owns its own
 * {@link CircuitComponent#stamp stamping logic}; the compiler's job is to
 * run union-find over the wires, build a {@link CircuitStampContext} backed
 * by typed stamp lists, and call {@code c.stamp(ctx)} on every component.
 *
 * <p>Adding a new component type means adding a record with its own
 * {@code stamp}. The compiler does not change.
 *
 * <p>Coils report their own Tesla-per-amp sensitivity; the compiler samples
 * the eigenfield shape into {@code ex/ey/ez} arrays scaled by that
 * sensitivity. There is no silent "R = 1 Ω" fallback — a coil with both
 * {@code seriesResistanceOhms == 0} and {@code selfInductanceHenry == 0}
 * is rejected at the {@link CircuitComponent.Coil} record level.
 */
public final class CircuitCompiler {

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
    // Stamp records — one per primitive MNA contribution
    // ─────────────────────────────────────────────────────────────────────

    private record ResistorStamp(int a, int b, double g) {}
    private record CapacitorStamp(int a, int b, double f) {}
    private record SwitchStamp(int a, int b, double closedOhms, double openOhms, double threshold, CtlBinding ctl, boolean invert) {}
    private record BranchStamp(int nodeA, int nodeB, VBranchKind kind, int refIndex, double r, double l) {}
    private record MixerStamp(int inNode, int out0Branch, int out1Branch, double loHz, ComplexPairFormat format) {}
    private record ModulatorStamp(int in0Node, int in1Node, int outBranch, double loHz, ComplexPairFormat format) {}
    private record MetadataStamp(int[] sourceIndices, int outBranch, MnaNetwork.MetadataMode mode) {}

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

        // Typed MNA stamps — one list per kind, ordered as encountered.
        private final List<ResistorStamp> resistors = new ArrayList<>();
        private final List<CapacitorStamp> capacitors = new ArrayList<>();
        private final List<SwitchStamp> switches = new ArrayList<>();
        private final List<BranchStamp> branches = new ArrayList<>();
        private final List<MixerStamp> mixers = new ArrayList<>();
        private final List<ModulatorStamp> modulators = new ArrayList<>();
        private final List<MetadataStamp> metadata = new ArrayList<>();

        // Source/coil/probe → branch index mappings, populated as registered.
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
            resistors.add(new ResistorStamp(a.index(), b.index(), 1.0 / ohms));
        }

        @Override
        public void stampCapacitor(Node a, Node b, double farads) {
            if (!(farads > 0)) throw new IllegalArgumentException("stampCapacitor: F must be > 0, got " + farads);
            capacitors.add(new CapacitorStamp(a.index(), b.index(), farads));
        }

        @Override
        public void stampInductor(Node a, Node b, double henry) {
            if (!(henry > 0)) throw new IllegalArgumentException("stampInductor: H must be > 0, got " + henry);
            addBranch(a.index(), b.index(), VBranchKind.PASSIVE_INDUCTOR, -1, 0, henry);
        }

        @Override
        public void stampSwitch(Node a, Node b, SwitchParams params) {
            switches.add(new SwitchStamp(a.index(), b.index(),
                params.closedOhms(), params.openOhms(), params.thresholdVolts(),
                params.ctl(), params.invert()));
        }

        @Override
        public void stampMixer(Node in, Node out0, Node out1, double loHz, ComplexPairFormat format) {
            // If every terminal is grounded the mixer is a no-op.
            if (in.isGround() && out0.isGround() && out1.isGround()) return;
            int mixerIndex = mixers.size();
            int b0 = out0.isGround() ? -1 :
                addBranch(out0.index(), Node.GROUND.index(), VBranchKind.MIXER_OUT_0, mixerIndex, 0, 0);
            int b1 = out1.isGround() ? -1 :
                addBranch(out1.index(), Node.GROUND.index(), VBranchKind.MIXER_OUT_1, mixerIndex, 0, 0);
            mixers.add(new MixerStamp(in.index(), b0, b1, loHz, format));
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
            int m = metadata.size();
            int branch = addBranch(outPort.index(), Node.GROUND.index(),
                VBranchKind.METADATA_OUT, m, 0, 0);
            metadata.add(new MetadataStamp(indices, branch, toNetworkMode(mode)));
        }

        /**
         * Resolve a metadata tap's {@code sourceName} to the set of compiled
         * source indices it should observe. A VoltageSource match is a single
         * index; a Modulator match expands to its I and Q sources.
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
                    return indices.stream().mapToInt(Integer::intValue).toArray();
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
            int m = modulators.size();
            int branch = addBranch(out.index(), Node.GROUND.index(),
                VBranchKind.MODULATOR_OUT, m, 0, 0);
            modulators.add(new ModulatorStamp(in0.index(), in1.index(), branch, loHz, format));
        }

        private static MnaNetwork.MetadataMode toNetworkMode(CircuitComponent.VoltageMetadata.Mode mode) {
            return switch (mode) {
                case ACTIVE -> MnaNetwork.MetadataMode.ACTIVE;
            };
        }

        @Override
        public void registerCoil(ComponentId id, String name, ProjectNodeId eigenfieldId,
                                 double selfInductanceHenry, double seriesResistanceOhms,
                                 double sensitivityT_per_A,
                                 Node inPort) {
            var sample = sampleEigenfield(eigenfieldId, repository, rMm, zMm, sensitivityT_per_A);
            int index = coils.size();
            coils.add(new CompiledCoil(id, name, selfInductanceHenry, seriesResistanceOhms,
                sample[0], sample[1], sample[2]));
            coilBranch.add(addBranch(inPort.index(), Node.GROUND.index(),
                VBranchKind.COIL, index, seriesResistanceOhms, selfInductanceHenry));
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
            // penalties (RF power, smoothness) weight.
            var rfNodes = new HashSet<Integer>();
            for (var m : modulators) {
                if (m.in0Node() >= 0) rfNodes.add(m.in0Node());
                if (m.in1Node() >= 0) rfNodes.add(m.in1Node());
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
                branches.size(),
                ints(resistors, ResistorStamp::a), ints(resistors, ResistorStamp::b), doubles(resistors, ResistorStamp::g),
                ints(capacitors, CapacitorStamp::a), ints(capacitors, CapacitorStamp::b), doubles(capacitors, CapacitorStamp::f),
                ints(switches, SwitchStamp::a), ints(switches, SwitchStamp::b),
                doubles(switches, SwitchStamp::closedOhms), doubles(switches, SwitchStamp::openOhms), doubles(switches, SwitchStamp::threshold),
                array(switches, SwitchStamp::ctl, CtlBinding[]::new), bools(switches, SwitchStamp::invert),
                ints(branches, BranchStamp::nodeA), ints(branches, BranchStamp::nodeB),
                array(branches, BranchStamp::kind, VBranchKind[]::new), ints(branches, BranchStamp::refIndex),
                doubles(branches, BranchStamp::r), doubles(branches, BranchStamp::l),
                toIntArray(sourceOutBranch),
                toIntArray(coilBranch), toIntArray(probeNode),
                ints(mixers, MixerStamp::inNode), ints(mixers, MixerStamp::out0Branch), ints(mixers, MixerStamp::out1Branch),
                doubles(mixers, MixerStamp::loHz),
                array(mixers, MixerStamp::format, ComplexPairFormat[]::new),
                metadata.stream().map(MetadataStamp::sourceIndices).toArray(int[][]::new),
                ints(metadata, MetadataStamp::outBranch),
                array(metadata, MetadataStamp::mode, MnaNetwork.MetadataMode[]::new),
                ints(modulators, ModulatorStamp::in0Node), ints(modulators, ModulatorStamp::in1Node),
                ints(modulators, ModulatorStamp::outBranch), doubles(modulators, ModulatorStamp::loHz),
                array(modulators, ModulatorStamp::format, ComplexPairFormat[]::new));
            return new CompiledCircuit(sources, coils, probes, network, channelCursor, rfOffsets);
        }

        private int addBranch(int nodeA, int nodeB, VBranchKind kind, int refIdx, double r, double l) {
            int idx = branches.size();
            branches.add(new BranchStamp(nodeA, nodeB, kind, refIdx, r, l));
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

    private static <T> int[] ints(List<T> list, java.util.function.ToIntFunction<T> field) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = field.applyAsInt(list.get(i));
        return out;
    }

    private static <T> double[] doubles(List<T> list, java.util.function.ToDoubleFunction<T> field) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = field.applyAsDouble(list.get(i));
        return out;
    }

    private static <T> boolean[] bools(List<T> list, java.util.function.Predicate<T> field) {
        boolean[] out = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = field.test(list.get(i));
        return out;
    }

    private static <T, R> R[] array(List<T> list, java.util.function.Function<T, R> field, java.util.function.IntFunction<R[]> ctor) {
        R[] out = ctor.apply(list.size());
        for (int i = 0; i < list.size(); i++) out[i] = field.apply(list.get(i));
        return out;
    }

    private static double[][][] sampleEigenfield(ProjectNodeId eigenfieldId,
                                                 ProjectRepository repository,
                                                 double[] rMm, double[] zMm,
                                                 double coilSensitivityT_per_A) {
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
        // Shape only from the eigenfield; magnitude comes from the coil.
        double scale = coilSensitivityT_per_A;

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
