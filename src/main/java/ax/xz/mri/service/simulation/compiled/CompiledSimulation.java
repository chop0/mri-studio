package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.field.CartesianGrid;
import ax.xz.mri.model.field.CylindricalGrid;
import ax.xz.mri.model.field.SpatialGrid;
import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.probe.ElectricalProbe;
import ax.xz.mri.model.probe.OpticalCounter;
import ax.xz.mri.model.probe.Probe;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.NvSimulationMethod;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.circuit.CircuitCompiler;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;
import ax.xz.mri.service.circuit.CompiledCircuit;
import ax.xz.mri.service.simulation.math.BlochStep;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.util.LruCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fused per-step simulation kernel.
 *
 * <p>Once compiled, the {@code CompiledSimulation} is a self-contained object
 * with one public surface for the whole simulator: full multi-probe runs,
 * single-spin trajectories, point-wise field samples, opaque snapshots.
 * Every cross-kind dispatch — symmetry-driven grid choice, per-substance
 * advance closure, per-probe collector closure, NV-NV cluster tiering —
 * happens once during {@link #compile} and is baked into flat tables the
 * inner loop reads with no {@code instanceof} checks.
 *
 * <p>Reciprocity is fully generalised: every magnetic-moment-emitting
 * substance contributes to a single per-coil coupling integral
 * {@code S_c = Σ_d E_c(x_d) · moment_d}, with weights pre-baked at compile
 * time. Adding a new substance kind means a new {@link CompiledSubstance}
 * impl + a new branch in {@link #compileSubstance}; the inner loop doesn't
 * change. Optical readout is explicit (substance ↔ {@link OpticalCounter}
 * wiring resolved at compile time), magnetic coupling is implicit / ambient.
 *
 * <p>Two output modes:
 * <ul>
 *   <li>{@link #runMultiProbe()} — full multi-spin sim with reciprocity,
 *       emits one {@link SignalTrace} per electrical probe.</li>
 *   <li>{@link #singleSpinTrajectory} — single isochromat sweep at an
 *       arbitrary 3-D point, used by the scrubbing UI and by procedures
 *       that need point-wise Bloch evolution without reciprocity feedback.</li>
 * </ul>
 *
 * <p>Per-step inner-loop cost is bounded as the {@linkplain
 * ax.xz.mri.model.field.CylindricalGrid cylindrical fast path} when every
 * eigenfield + substance opts into axisymmetric symmetry; falls back to
 * {@link CartesianGrid} otherwise. The selection is bit-identical: the
 * symmetry detector matches today's compile-time choice so cylindrical
 * setups continue running at the historic speed and numerics.
 */
public final class CompiledSimulation {

    /* ── Compile-time bake ─────────────────────────────────────────── */

    private final SpatialGrid grid;
    private final CompiledCircuit circuit;
    private final List<Segment> segments;
    private final List<PulseSegment> pulse;
    private final List<Substance> substances;
    private final CompiledSubstance[] kernels;
    private final int[] stateOffset;
    private final int fusedStateSize;
    private final double b0Ref;
    private final List<? extends Probe<?>> probes;
    private final List<ElectricalProbe> electricalProbes;
    private final List<OpticalCounter> opticalCounters;
    private final int[] opticalCounterSubstanceIndex;   // per OpticalCounter: which substance's port it reads
    private final int[] opticalCounterChannelIndex;     // per OpticalCounter: which channel of that substance

    /**
     * Per-substance per-control-input channel offset into {@link
     * ax.xz.mri.model.sequence.PulseStep#controls() PulseStep.controls()}.
     * {@code substanceControlChannels[s][k] = -1} ⇒ the {@code k}-th control
     * input on substance {@code s} has no wired source (read as 0 each step).
     */
    private final int[][] substanceControlChannels;
    /** Per-substance reusable buffer of length {@code kernels[s].controlInputCount()}. */
    private final double[][] controlInputsBuffer;
    /** Per-substance scratch buffer of {@code 3 · spinCount()} reals (no — see ratesBuffer). */
    private final double[][] ratesBuffer;

    /**
     * Per-substance per-coil sensitivity at every spin. Shape:
     * {@code coilSensitivityE[s][c]} is a {@code double[]} of length
     * {@code 3 * kernels[s].spinCount()} laid out as
     * {@code [Ex0,Ey0,Ez0, Ex1,Ey1,Ez1, …]}, pre-multiplied by the coil's
     * {@code sensitivityT_per_A}. Used identically for transmit (computing
     * local B at each spin from coil drives) and receive (computing
     * reciprocity coupling from per-spin moments).
     */
    private final double[][][] coilSensitivityE;

    /**
     * Transmit-only coil currents per pulse step, cached at compile time.
     * Layout: {@code coilDriveI[stepIndex][coilIndex]}, with sibling
     * {@code coilDriveQ}. Drives the {@link #singleSpinTrajectory}
     * shortcut and (when reciprocity is negligible) the no-EMF advance.
     */
    private final double[][] transmitOnlyCoilI;
    private final double[][] transmitOnlyCoilQ;

    /**
     * Steady-state coil drives from STATIC sources only — solved once at
     * compile time with all controls set to zero. {@link #sampleAt} folds
     * these into {@link FieldSample#staticBz} so {@code staticBz} carries
     * the true total static Bz at the point (B0 plus every STATIC-driven
     * gradient / shim coil), not just the rotating-frame reference.
     */
    private final double[] staticCoilDriveI;
    private final double[] staticCoilDriveQ;

    /** Flat list of (segmentIndex, stepIndex within segment, dt) for fast inner-loop iteration. */
    private final int[] stepSegment;
    private final int[] stepIndexInSegment;
    private final double[] stepDt;
    private final double[] stepStartTimeSeconds;

    /* ── Mutable simulation state (only touched inside run* methods) ── */

    private final double[] state;
    private final double[] momentsBuffer;
    private final double[] sCoilRe;
    private final double[] sCoilIm;
    private final double[] sCoilRePrev;
    private final double[] sCoilImPrev;
    private final double[] emfRe;
    private final double[] emfIm;
    private final double[] localB;
    private double timeSeconds;
    private int currentStepIndex;

    private final CircuitStepEvaluator evaluator;

    /**
     * Cache of single-spin trajectories keyed on position. Sized for the
     * geometry-shading sweep (20 radial × ~80 axial = ~1600 points) plus
     * user isochromats. Per-trajectory cost is one full pulse sweep
     * (a few ms); cache means subsequent scrubs through the same cursor
     * domain re-use the existing trajectories rather than re-walking.
     */
    private final LruCache<Long, Trajectory> trajectoryCache = new LruCache<>(2048);

    /* ── Compile ──────────────────────────────────────────────────── */

    public record CompileRequest(
        CircuitDocument circuit,
        ProjectState repository,
        List<Substance> substances,
        List<Segment> segments,
        List<PulseSegment> pulse,
        double b0Ref,
        NvSimulationMethod nvMethod
    ) {
        public CompileRequest {
            substances = substances == null ? List.of() : List.copyOf(substances);
            segments = segments == null ? List.of() : List.copyOf(segments);
            pulse = pulse == null ? List.of() : List.copyOf(pulse);
            if (nvMethod == null) nvMethod = NvSimulationMethod.independent();
        }

        /** Independent-NV (classical Bloch) method — the default when none is given. */
        public CompileRequest(CircuitDocument circuit, ProjectState repository,
                              List<Substance> substances, List<Segment> segments,
                              List<PulseSegment> pulse, double b0Ref) {
            this(circuit, repository, substances, segments, pulse, b0Ref, NvSimulationMethod.independent());
        }
    }

    /**
     * Compile a self-contained, run-ready simulation.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Decide grid symmetry from eigenfield
     *       {@link EigenfieldDocument#symmetry()} ∨ substance
     *       {@link Substance#preferredSymmetry()}. Any cartesian opt-in
     *       forces 3D; otherwise stay cylindrical.</li>
     *   <li>Build the corresponding {@link SpatialGrid} ({@link CylindricalGrid}
     *       or {@link CartesianGrid}) sized to the union of substance
     *       extents at the finest substance-declared resolution.</li>
     *   <li>{@link CircuitCompiler#compile Compile} the circuit on that grid.</li>
     *   <li>Compile each substance into a {@link CompiledSubstance} (pattern
     *       match over the {@link Substance} sealed hierarchy).</li>
     *   <li>Bake per-substance per-coil per-spin sensitivity tables — the
     *       only thing the per-step inner loop indexes for both transmit
     *       and reciprocity.</li>
     *   <li>Bake per-step transmit-only coil currents (single MNA prepass).</li>
     *   <li>Allocate working buffers, return.</li>
     * </ol>
     */
    public static CompiledSimulation compile(CompileRequest req) {
        var symmetry = pickSymmetry(req);
        var grid = buildGrid(req.substances(), symmetry);
        var compiledCircuit = CircuitCompiler.compile(req.circuit(), req.repository(), grid);

        var kernels = new CompiledSubstance[req.substances().size()];
        var stateOffset = new int[kernels.length];
        int fusedSize = 0;
        for (int i = 0; i < kernels.length; i++) {
            kernels[i] = compileSubstance(req.substances().get(i), grid, req.nvMethod());
            stateOffset[i] = fusedSize;
            fusedSize += kernels[i].stateSize();
        }

        return new CompiledSimulation(req, symmetry, grid, compiledCircuit, kernels, stateOffset, fusedSize);
    }

    private CompiledSimulation(CompileRequest req, FieldSymmetry symmetry, SpatialGrid grid,
                               CompiledCircuit circuit, CompiledSubstance[] kernels,
                               int[] stateOffset, int fusedStateSize) {
        this.grid = grid;
        this.circuit = circuit;
        this.segments = req.segments();
        this.pulse = req.pulse();
        this.substances = List.copyOf(req.substances());
        this.kernels = kernels;
        this.stateOffset = stateOffset;
        this.fusedStateSize = fusedStateSize;
        this.b0Ref = req.b0Ref();

        // 1. Build the typed probe list off the compiled-circuit metadata.
        //    Electrical probes come from the MNA-compiled probe list. Optical
        //    counters come from CircuitComponent.OpticalCounter blocks + wires.
        var probes = new ArrayList<Probe<?>>();
        var electricals = new ArrayList<ElectricalProbe>();
        for (var p : circuit.probes()) {
            var ep = new ElectricalProbe(p.id(), p.name(), p.gain(), p.demodPhaseDeg(),
                p.loadImpedanceOhms() > 0 ? p.loadImpedanceOhms() : 50.0);
            probes.add(ep);
            electricals.add(ep);
        }
        this.electricalProbes = List.copyOf(electricals);

        // Resolve substance schematic-block id → kernel index. SimulationCompiler
        // builds the substance list in CircuitDocument.components() order, so
        // substance blocks map one-to-one onto kernels in that order.
        var substanceIndexByBlockId = new java.util.HashMap<ax.xz.mri.model.circuit.ComponentId, Integer>();
        if (req.circuit() != null) {
            int subIdx = 0;
            for (var comp : req.circuit().components()) {
                if (comp instanceof ax.xz.mri.model.circuit.CircuitComponent.Substance block) {
                    if (subIdx < kernels.length) substanceIndexByBlockId.put(block.id(), subIdx);
                    subIdx++;
                }
            }
        }

        // Build OpticalCounter probes from schematic blocks + wires.
        var counterList = new ArrayList<OpticalCounter>();
        var counterSubIdx = new ArrayList<Integer>();
        var counterChIdx = new ArrayList<Integer>();
        if (req.circuit() != null) {
            for (var comp : req.circuit().components()) {
                if (!(comp instanceof ax.xz.mri.model.circuit.CircuitComponent.OpticalCounter ocBlock)) continue;
                // Find the wire bridging this counter's "in" port to a substance's clicks_<x> port.
                String substanceBlockId = null;
                String channelName = null;
                for (var wire : req.circuit().wires()) {
                    if (wire.to().componentId().equals(ocBlock.id()) && wire.to().port().equals("in")
                        && wire.from().port().startsWith("clicks_")) {
                        substanceBlockId = wire.from().componentId().value();
                        channelName = wire.from().port().substring("clicks_".length());
                        break;
                    }
                    if (wire.from().componentId().equals(ocBlock.id()) && wire.from().port().equals("in")
                        && wire.to().port().startsWith("clicks_")) {
                        substanceBlockId = wire.to().componentId().value();
                        channelName = wire.to().port().substring("clicks_".length());
                        break;
                    }
                }
                if (substanceBlockId == null) continue;
                var subBlockComponentId = new ax.xz.mri.model.circuit.ComponentId(substanceBlockId);
                Integer subIdx = substanceIndexByBlockId.get(subBlockComponentId);
                if (subIdx == null) continue;
                int chIdx = kernels[subIdx].opticalChannelNames().indexOf(channelName);
                if (chIdx < 0) continue;
                var probe = new OpticalCounter(
                    ocBlock.id(), ocBlock.name(),
                    substanceBlockId, "clicks_" + channelName,
                    ocBlock.quantumEfficiency(), ocBlock.darkRateHz(), ocBlock.seed());
                counterList.add(probe);
                counterSubIdx.add(subIdx);
                counterChIdx.add(chIdx);
                probes.add(probe);
            }
        }
        this.probes = List.copyOf(probes);
        this.opticalCounters = List.copyOf(counterList);
        this.opticalCounterSubstanceIndex = counterSubIdx.stream().mapToInt(Integer::intValue).toArray();
        this.opticalCounterChannelIndex = counterChIdx.stream().mapToInt(Integer::intValue).toArray();

        // Resolve substance control-input wires → control-vector channel offsets.
        // For each substance kernel s + each control-input name, find a wire
        // pointing to (substanceBlock.id, controlName) and resolve the source
        // side's channel offset on the compiled circuit. Sources reached
        // indirectly (e.g. through a Modulator) aren't supported here — CONTROL
        // inputs expect a direct sequence-track-driven source.
        this.substanceControlChannels = new int[kernels.length][];
        this.controlInputsBuffer = new double[kernels.length][];
        var blockIdBySubIdx = new ax.xz.mri.model.circuit.ComponentId[kernels.length];
        substanceIndexByBlockId.forEach((blockId, idx) -> blockIdBySubIdx[idx] = blockId);
        for (int s = 0; s < kernels.length; s++) {
            int nControl = kernels[s].controlInputCount();
            substanceControlChannels[s] = new int[nControl];
            controlInputsBuffer[s] = new double[nControl];
            var names = kernels[s].controlInputNames();
            var blockId = blockIdBySubIdx[s];
            for (int k = 0; k < nControl; k++) {
                substanceControlChannels[s][k] = -1;
                if (blockId == null || req.circuit() == null) continue;
                String portName = names.get(k);
                for (var wire : req.circuit().wires()) {
                    ax.xz.mri.model.circuit.ComponentId sourceId = null;
                    if (wire.to().componentId().equals(blockId) && wire.to().port().equals(portName)) {
                        sourceId = wire.from().componentId();
                    } else if (wire.from().componentId().equals(blockId) && wire.from().port().equals(portName)) {
                        sourceId = wire.to().componentId();
                    }
                    if (sourceId == null) continue;
                    for (var compiledSrc : circuit.sources()) {
                        if (compiledSrc.id().equals(sourceId)) {
                            substanceControlChannels[s][k] = compiledSrc.channelOffset();
                            break;
                        }
                    }
                    if (substanceControlChannels[s][k] >= 0) break;
                }
            }
        }

        // Per-substance scratch buffer of length spinCount() * opticalChannelCount().
        this.ratesBuffer = new double[kernels.length][];
        for (int s = 0; s < kernels.length; s++) {
            int n = Math.max(1, kernels[s].spinCount() * Math.max(1, kernels[s].opticalChannelCount()));
            ratesBuffer[s] = new double[n];
        }

        // 2. Bake per-substance per-coil per-spin sensitivities.
        int nCoils = circuit.coils().size();
        this.coilSensitivityE = new double[kernels.length][nCoils][];
        for (int s = 0; s < kernels.length; s++) {
            var kernel = kernels[s];
            int nSpins = kernel.spinCount();
            for (int c = 0; c < nCoils; c++) {
                var coil = circuit.coils().get(c);
                double[] flat = new double[3 * nSpins];
                for (int i = 0; i < nSpins; i++) {
                    Vec3 p = kernel.spinPosition(i);
                    Vec3 e = grid.sampleVec3(coil.ex(), coil.ey(), coil.ez(), p.x(), p.y(), p.z());
                    flat[3 * i    ] = e.x();
                    flat[3 * i + 1] = e.y();
                    flat[3 * i + 2] = e.z();
                }
                coilSensitivityE[s][c] = flat;
            }
        }

        // 3. Build the flat step program + transmit-only coil currents prepass.
        int stepCount = 0;
        for (int si = 0; si < segments.size() && si < pulse.size(); si++) {
            stepCount += pulse.get(si).steps().size();
        }
        this.stepSegment = new int[stepCount];
        this.stepIndexInSegment = new int[stepCount];
        this.stepDt = new double[stepCount];
        this.stepStartTimeSeconds = new double[stepCount + 1];
        this.transmitOnlyCoilI = new double[stepCount][nCoils];
        this.transmitOnlyCoilQ = new double[stepCount][nCoils];

        this.evaluator = new CircuitStepEvaluator(circuit);
        double omegaSim = b0Ref * 0.0;  // Reference frequency for MNA — defer to the moment-emitter substance's γ.
        // The legacy pipeline uses γ_proton · b0Ref. For substance-aware compilation we pick the dominant
        // BLOCH substance's γ to keep numerical parity with the pre-substance pipeline.
        double omegaSimFromSubstance = pickRotatingFrameOmega();
        int mnaChannelCount = circuit.totalChannelCount();
        double[] paddedControls = new double[Math.max(1, mnaChannelCount)];
        double t = 0;
        int k = 0;
        for (int si = 0; si < segments.size() && si < pulse.size(); si++) {
            double dt = segments.get(si).dt();
            var steps = pulse.get(si).steps();
            for (int j = 0; j < steps.size(); j++) {
                // Defensive copy into a fixed-size buffer matching the MNA's
                // channel count. Stale bakes (sequence baked against an older
                // circuit version) can deliver a shorter controls vector;
                // padding with zeros keeps the MNA's source-offset reads safe.
                fillPadded(paddedControls, steps.get(j).controls());
                evaluator.evaluate(paddedControls, dt, null, null, t, omegaSimFromSubstance);
                for (int c = 0; c < nCoils; c++) {
                    transmitOnlyCoilI[k][c] = evaluator.coilDriveI(c);
                    transmitOnlyCoilQ[k][c] = evaluator.coilDriveQ(c);
                }
                stepSegment[k] = si;
                stepIndexInSegment[k] = j;
                stepDt[k] = dt;
                stepStartTimeSeconds[k] = t;
                t += dt;
                k++;
            }
        }
        stepStartTimeSeconds[stepCount] = t;

        // 3b. Static-only solve: zero controls, single step, captures STATIC sources'
        //     steady-state coil drives. sampleAt() folds these into FieldSample.staticBz
        //     so procedures see total static Bz (B0 + gradient + shim coils).
        //
        // Size the zero-controls vector to the circuit's *actual* total channel
        // count rather than the first pulse step's length — when the pulse list
        // is empty the first-step inspection falls back to 1, and any source
        // with channelOffset ≥ 1 then ArrayIndexOutOfBounds when the MNA reads
        // controls[channelOffset].
        this.staticCoilDriveI = new double[nCoils];
        this.staticCoilDriveQ = new double[nCoils];
        evaluator.resetHistory();
        int zeroControlsLength = Math.max(
            circuit.totalChannelCount(),
            controlsLength(pulse));
        double[] zeroControls = new double[Math.max(1, zeroControlsLength)];
        double staticDt = stepCount > 0 ? stepDt[0] : 1e-6;
        evaluator.evaluate(zeroControls, staticDt, null, null, 0, omegaSimFromSubstance);
        for (int c = 0; c < nCoils; c++) {
            staticCoilDriveI[c] = evaluator.coilDriveI(c);
            staticCoilDriveQ[c] = evaluator.coilDriveQ(c);
        }
        evaluator.resetHistory();

        // 4. Allocate mutable state buffers (zero-allocation hot path after this).
        this.state = new double[fusedStateSize];
        int totalSpinCount = 0;
        for (var kn : kernels) totalSpinCount += kn.spinCount();
        this.momentsBuffer = new double[Math.max(1, 3 * totalSpinCount)];
        this.sCoilRe = new double[nCoils];
        this.sCoilIm = new double[nCoils];
        this.sCoilRePrev = new double[nCoils];
        this.sCoilImPrev = new double[nCoils];
        this.emfRe = new double[nCoils];
        this.emfIm = new double[nCoils];
        this.localB = new double[Math.max(1, 3 * maxSpinCount())];
        this.timeSeconds = 0;
        this.currentStepIndex = 0;
    }

    /* ── Substance dispatch (compile time) ─────────────────────────── */

    private static CompiledSubstance compileSubstance(Substance s, SpatialGrid grid, NvSimulationMethod nvMethod) {
        return switch (s) {
            case ContinuousMagnetisation cm -> new BlochKernel(cm, grid);
            case NvEnsemble nv -> {
                int[][] clusters = switch (nvMethod) {
                    case NvSimulationMethod.ClusteredQubitHamiltonian h ->
                        NvClusterUnion.union(nv.centres(), h.couplingCutoffMetres(), h.maxClusterSize());
                };
                yield new NvKernel(nv, clusters);
            }
        };
    }

    /* ── Symmetry picker ───────────────────────────────────────────── */

    /**
     * Cartesian wins if any substance or eigenfield opts in; otherwise stay
     * cylindrical. No override knob — symmetry is a property of the inputs.
     */
    private static FieldSymmetry pickSymmetry(CompileRequest req) {
        for (var s : req.substances()) {
            if (s.preferredSymmetry() == FieldSymmetry.CARTESIAN_3D) return FieldSymmetry.CARTESIAN_3D;
        }
        if (req.circuit() != null) {
            for (var comp : req.circuit().components()) {
                if (comp instanceof ax.xz.mri.model.circuit.CircuitComponent.Coil coil) {
                    ProjectNodeId eid = coil.eigenfieldId();
                    if (eid == null || req.repository() == null) continue;
                    if (req.repository().node(eid) instanceof EigenfieldDocument doc
                        && doc.symmetry() == FieldSymmetry.CARTESIAN_3D) {
                        return FieldSymmetry.CARTESIAN_3D;
                    }
                }
            }
        }
        return FieldSymmetry.AXISYMMETRIC_Z;
    }

    /* ── Grid builder ─────────────────────────────────────────────── */

    /**
     * Default eigenfield-bake resolution for substances that don't declare
     * one. NV-only setups land here, and the dipole-pair sample field varies
     * on the scale of the source depth (∼50 nm) — too coarse a bake and the
     * tri-linear interpolation at NV positions overshoots wildly (a 21-pt
     * grid was off by 4× before this was raised). 41 keeps the bake cheap
     * (~70 k script calls) while halving the spacing.
     */
    private static final int DEFAULT_BAKE_N = 41;
    /** Fallback half-extent (m) when no substance declares a non-degenerate extent on an axis. */
    private static final double FALLBACK_HALF_EXTENT = 1e-9;

    /**
     * Build the eigenfield-bake grid from substances. The grid extent is the
     * union of substance half-extents (so every spin sits inside the bake
     * domain) — NOT inflated to any minimum, because an inflated grid coarsens
     * the bake at the actual substance positions. For an NV ensemble at z=50nm
     * over ±0.5 µm in x, inflating the grid to ±1 µm × ±1 µm × ±1 µm at 21
     * points puts the z-spacing at 100 nm while the dipole field varies on the
     * scale of the source depth (50 nm) — interpolation between z=0 (huge) and
     * z=100 nm (tiny) gives 4× the correct value at z=50 nm. Honesty about
     * the substance footprint is the only safe approach.
     *
     * <p>Resolution follows {@link ContinuousMagnetisation}'s declared grid
     * when present (the grid IS the substance's voxel layout in that case),
     * else falls back to {@link #DEFAULT_BAKE_N} per axis.
     */
    private static SpatialGrid buildGrid(List<Substance> substances, FieldSymmetry sym) {
        double halfX = 0, halfY = 0, halfZ = 0;
        int nX = DEFAULT_BAKE_N, nY = DEFAULT_BAKE_N, nZ = DEFAULT_BAKE_N;
        for (var s : substances) {
            var h = s.halfExtent();
            halfX = Math.max(halfX, h.x());
            halfY = Math.max(halfY, h.y());
            halfZ = Math.max(halfZ, h.z());
            if (s instanceof ContinuousMagnetisation cm) {
                nX = Math.max(nX, cm.nX());
                nY = Math.max(nY, cm.nY());
                nZ = Math.max(nZ, cm.nZ());
            }
        }
        if (halfX <= 0) halfX = FALLBACK_HALF_EXTENT;
        if (halfY <= 0) halfY = FALLBACK_HALF_EXTENT;
        if (halfZ <= 0) halfZ = FALLBACK_HALF_EXTENT;
        return switch (sym) {
            case AXISYMMETRIC_Z -> new CylindricalGrid(
                linspace(0, halfX, nX),
                linspace(-halfZ, halfZ, nZ));
            case CARTESIAN_3D -> new CartesianGrid(
                linspace(-halfX, halfX, nX),
                linspace(-halfY, halfY, nY),
                linspace(-halfZ, halfZ, nZ));
        };
    }

    private static double[] linspace(double a, double b, int n) {
        if (n < 2) return new double[]{a};
        var out = new double[n];
        for (int i = 0; i < n; i++) out[i] = a + (b - a) * i / (n - 1);
        return out;
    }

    private int maxSpinCount() {
        int m = 0;
        for (var k : kernels) m = Math.max(m, k.spinCount());
        return m;
    }

    /**
     * The control-vector length the MNA solver expects, inferred from the first
     * non-empty pulse step. Used by the static-only bake (controls all zero)
     * and any other path that needs a zero-controls evaluation outside the
     * sequence loop.
     */
    private static int controlsLength(List<PulseSegment> pulse) {
        for (var seg : pulse) {
            for (var step : seg.steps()) {
                return step.controls().length;
            }
        }
        return 0;
    }

    /** Defensive copy that fills a fixed-size buffer from a possibly-shorter source. */
    private static void fillPadded(double[] dst, double[] src) {
        int n = Math.min(dst.length, src.length);
        System.arraycopy(src, 0, dst, 0, n);
        for (int i = n; i < dst.length; i++) dst[i] = 0.0;
    }

    /* ── Public surface (non-leaky) ────────────────────────────────── */

    public SpatialGrid grid() { return grid; }
    public List<Segment> segments() { return segments; }
    public List<PulseSegment> pulse() { return pulse; }
    public List<Substance> substances() { return substances; }
    public CompiledCircuit circuit() { return circuit; }
    public double b0Ref() { return b0Ref; }
    public List<? extends Probe<?>> probes() { return probes; }
    public List<ElectricalProbe> electricalProbes() { return electricalProbes; }
    public List<OpticalCounter> opticalCounters() { return opticalCounters; }
    public int substanceCount() { return kernels.length; }
    public CompiledSubstance compiledSubstance(int i) { return kernels[i]; }

    /** Number of compiled per-dt steps across the full sequence. */
    public int stepCount() { return stepDt.length; }

    /** Microsecond timestamp at the start of step {@code i} (or end of sequence at {@code stepCount()}). */
    public double stepTimeMicros(int i) { return stepStartTimeSeconds[i] * 1e6; }

    /** Sample the static B field (Tesla, rotating-frame-referenced) at an arbitrary point. */
    public FieldSample sampleAt(Vec3 position) {
        return sampleAt(position.x(), position.y(), position.z());
    }

    public FieldSample sampleAt(double xMetres, double yMetres, double zMetres) {
        int nCoils = circuit.coils().size();
        double[] ex = new double[nCoils];
        double[] ey = new double[nCoils];
        double[] ez = new double[nCoils];
        // Rotating-frame B0 offset.
        double staticBz = -b0Ref;
        for (int i = 0; i < nCoils; i++) {
            var coil = circuit.coils().get(i);
            Vec3 e = grid.sampleVec3(coil.ex(), coil.ey(), coil.ez(), xMetres, yMetres, zMetres);
            ex[i] = e.x(); ey[i] = e.y(); ez[i] = e.z();
            // STATIC source steady-state contribution: I·ez (gradient / shim / B0 bias).
            staticBz += staticCoilDriveI[i] * ez[i];
        }
        // Continuous magnetisation thermal-equilibrium initial state at this point.
        double mx0 = 0, my0 = 0, mz0 = primaryMz0();
        return new FieldSample(staticBz, mx0, my0, mz0, ex, ey, ez);
    }

    /** The rotating-frame γ — picked from the dominant Bloch substance's γ, NV's γ_e if NV-only, or proton-default if absent. */
    private double pickRotatingFrameOmega() {
        for (var s : substances) {
            if (s instanceof ContinuousMagnetisation cm) {
                return cm.gammaRadPerSecPerTesla() * b0Ref;
            }
        }
        for (var s : substances) {
            if (s instanceof NvEnsemble) {
                return 2.0 * Math.PI * 28.024e9 * b0Ref;
            }
        }
        return 267.522e6 * b0Ref;
    }

    /** Initial mz used by point-wise sampling when the caller doesn't override it. */
    private double primaryMz0() {
        for (var s : substances) {
            if (s instanceof ContinuousMagnetisation cm) return cm.mz0();
        }
        return 1.0;
    }

    /* ── runMultiProbe: full multi-spin sim with reciprocity ───────── */

    /**
     * Run the full baked sequence, emitting one {@link SignalTrace} per
     * electrical probe in the circuit. Reciprocity feedback is included.
     *
     * <p>Per-step inner loop:
     * <ol>
     *   <li>Each substance kernel emits per-spin magnetic moments.</li>
     *   <li>For each coil c: integrate the complex transverse coupling
     *       {@code S_c = Σ E_c·m_⊥} across all substances.</li>
     *   <li>{@code EMF_c = −(S_c − S_c_prev)/dt}.</li>
     *   <li>Probe-side MNA solve with EMF stamped; record probe voltages.</li>
     *   <li>Transmit-side MNA solve (no EMF); recompute coil drives.</li>
     *   <li>For each substance: compute local-B at every spin from coil
     *       drives and the pre-baked sensitivities; call
     *       {@link CompiledSubstance#advance}.</li>
     *   <li>Save {@code S_prev} ← {@code S}; advance time.</li>
     * </ol>
     */
    public MultiProbeSignalTrace runMultiProbe() {
        // Run even when there are no electrical probes — optical counters still
        // generate traces. An empty kernel list short-circuits.
        if (kernels.length == 0) return MultiProbeSignalTrace.empty();
        reset();

        int nProbes = circuit.probes().size();
        int nCoils = circuit.coils().size();
        var traces = new ArrayList<List<Point>>(nProbes);
        double[] cosPhase = new double[nProbes];
        double[] sinPhase = new double[nProbes];
        for (int k = 0; k < nProbes; k++) {
            var p = circuit.probes().get(k);
            var list = new ArrayList<Point>();
            list.add(new Point(0, 0, 0));
            traces.add(list);
            double rad = p.demodPhaseDeg() * Math.PI / 180.0;
            cosPhase[k] = Math.cos(rad);
            sinPhase[k] = Math.sin(rad);
        }

        // One trace per optical counter; primary in stable counter-order.
        var opticalTraces = new ArrayList<List<Point>>(opticalCounters.size());
        for (int oc = 0; oc < opticalCounters.size(); oc++) {
            var list = new ArrayList<Point>();
            list.add(new Point(0, 0, 0));
            opticalTraces.add(list);
        }

        double omegaSim = pickRotatingFrameOmega();
        int mnaChannelCount = circuit.totalChannelCount();
        double[] stepControls = new double[Math.max(1, mnaChannelCount)];
        Arrays.fill(sCoilRePrev, 0);
        Arrays.fill(sCoilImPrev, 0);

        for (int step = 0; step < stepCount(); step++) {
            double dt = stepDt[step];
            int segIdx = stepSegment[step];
            var pulseStep = pulse.get(segIdx).steps().get(stepIndexInSegment[step]);
            fillPadded(stepControls, pulseStep.controls());

            // 1. Aggregate per-coil reciprocity coupling across substances.
            Arrays.fill(sCoilRe, 0);
            Arrays.fill(sCoilIm, 0);
            for (int s = 0; s < kernels.length; s++) {
                var kn = kernels[s];
                kn.emitMagneticMoments(state, stateOffset[s], momentsBuffer);
                int nSpins = kn.spinCount();
                for (int c = 0; c < nCoils; c++) {
                    double[] E = coilSensitivityE[s][c];
                    double re = 0, im = 0;
                    for (int i = 0; i < nSpins; i++) {
                        double mx = momentsBuffer[3 * i];
                        double my = momentsBuffer[3 * i + 1];
                        re += E[3 * i] * mx + E[3 * i + 1] * my;
                        im += E[3 * i] * my - E[3 * i + 1] * mx;
                    }
                    sCoilRe[c] += re;
                    sCoilIm[c] += im;
                }
            }

            // 2. EMF.
            for (int c = 0; c < nCoils; c++) {
                emfRe[c] = -(sCoilRe[c] - sCoilRePrev[c]) / dt;
                emfIm[c] = -(sCoilIm[c] - sCoilImPrev[c]) / dt;
            }

            // 3. Probe-side solve with EMF. Use the padded controls so a stale
            // bake (controls too short for the current circuit) doesn't IOOBE
            // the MNA's source-offset reads.
            evaluator.evaluate(stepControls, dt, emfRe, emfIm, timeSeconds, omegaSim);
            double tNext = timeSeconds + dt;
            double tUs = Math.round(tNext * 1e7) / 10.0;
            double labPhase = omegaSim * tNext;
            double cLab = Math.cos(labPhase), sLab = Math.sin(labPhase);
            for (int pk = 0; pk < nProbes; pk++) {
                double simR = evaluator.probeVoltageReal(pk);
                double simI = evaluator.probeVoltageImag(pk);
                double labR = simR * cLab - simI * sLab;
                double labI = simR * sLab + simI * cLab;
                var probe = circuit.probes().get(pk);
                double phasedR = (labR * cosPhase[pk] - labI * sinPhase[pk]) * probe.gain();
                double phasedI = (labR * sinPhase[pk] + labI * cosPhase[pk]) * probe.gain();
                traces.get(pk).add(new Point(tUs, phasedR, phasedI));
            }

            // 4. Transmit-only currents (cached at compile time) drive substance advance.
            double[] iRow = transmitOnlyCoilI[step];
            double[] qRow = transmitOnlyCoilQ[step];
            double[] controls = stepControls;
            for (int s = 0; s < kernels.length; s++) {
                var kn = kernels[s];
                int nSpins = kn.spinCount();
                for (int i = 0; i < nSpins; i++) {
                    double bx = 0, by = 0, bz = -b0Ref;
                    for (int c = 0; c < nCoils; c++) {
                        double[] E = coilSensitivityE[s][c];
                        double ex = E[3 * i];
                        double ey = E[3 * i + 1];
                        double ez = E[3 * i + 2];
                        bx += iRow[c] * ex - qRow[c] * ey;
                        by += iRow[c] * ey + qRow[c] * ex;
                        bz += iRow[c] * ez;
                    }
                    localB[3 * i    ] = bx;
                    localB[3 * i + 1] = by;
                    localB[3 * i + 2] = bz;
                }
                // Populate control inputs from the per-step controls vector.
                var cBuf = controlInputsBuffer[s];
                for (int k = 0; k < cBuf.length; k++) {
                    int ch = substanceControlChannels[s][k];
                    cBuf[k] = (ch >= 0 && ch < controls.length) ? controls[ch] : 0.0;
                }
                kn.advance(state, stateOffset[s], localB, cBuf, dt, timeSeconds);
            }

            // 4b. Aggregate optical-counter clicks across spins after substance advance.
            for (int oc = 0; oc < opticalCounters.size(); oc++) {
                int s = opticalCounterSubstanceIndex[oc];
                int ch = opticalCounterChannelIndex[oc];
                var kn = kernels[s];
                int nChannels = Math.max(1, kn.opticalChannelCount());
                int nSpins = kn.spinCount();
                kn.emitPhotonClickRates(state, stateOffset[s], ratesBuffer[s]);
                double totalRate = 0;
                for (int i = 0; i < nSpins; i++) totalRate += ratesBuffer[s][i * nChannels + ch];
                var ocProbe = opticalCounters.get(oc);
                double effectiveRate = totalRate * ocProbe.quantumEfficiency() + ocProbe.darkRateHz();
                double clicksThisStep = effectiveRate * dt;
                opticalTraces.get(oc).add(new Point(tUs, clicksThisStep, 0));
            }

            // 5. Roll forward.
            System.arraycopy(sCoilRe, 0, sCoilRePrev, 0, nCoils);
            System.arraycopy(sCoilIm, 0, sCoilImPrev, 0, nCoils);
            timeSeconds = tNext;
            currentStepIndex = step + 1;
        }

        var byProbe = new LinkedHashMap<String, SignalTrace>();
        for (int k = 0; k < nProbes; k++) {
            byProbe.put(circuit.probes().get(k).name(), new SignalTrace(List.copyOf(traces.get(k))));
        }
        for (int oc = 0; oc < opticalCounters.size(); oc++) {
            byProbe.put(opticalCounters.get(oc).name(), new SignalTrace(List.copyOf(opticalTraces.get(oc))));
        }
        String primary = nProbes > 0
            ? circuit.probes().get(0).name()
            : (opticalCounters.isEmpty() ? null : opticalCounters.get(0).name());
        return new MultiProbeSignalTrace(Map.copyOf(byProbe), primary);
    }

    /* ── singleSpinTrajectory: one isochromat through the whole pulse ─ */

    /**
     * Single-spin (single-isochromat) Bloch trajectory at an arbitrary
     * 3-D point. Uses the {@linkplain #transmitOnlyCoilI compile-time-baked}
     * coil currents and a fresh field sample at the point. No reciprocity
     * feedback (consistent with the legacy {@code BlochSimulator}).
     *
     * <p>The substance whose physics drives the trajectory is the primary
     * {@link ContinuousMagnetisation} in the simulation; if none is present
     * (NV-only sims) the call returns {@code null}.
     */
    public Trajectory singleSpinTrajectory(Vec3 position) {
        long key = trajectoryKey(position);
        var cached = trajectoryCache.get(key);
        if (cached != null) return cached;
        var fresh = computeSingleSpinTrajectory(position);
        if (fresh != null) trajectoryCache.put(key, fresh);
        return fresh;
    }

    private Trajectory computeSingleSpinTrajectory(Vec3 position) {
        var cm = primaryContinuousMagnetisation();
        if (cm == null) return null;
        double gamma = cm.gammaRadPerSecPerTesla();
        double t1 = cm.t1Seconds();
        double t2 = cm.t2Seconds();
        var sample = sampleAt(position);
        int nCoils = circuit.coils().size();

        double mx = sample.mx0(), my = sample.my0(), mz = sample.mz0();
        int stepCount = stepCount();
        var out = new double[(stepCount + 1) * 5];
        int oi = 0;
        for (int step = 0; step < stepCount; step++) {
            out[oi++] = round1(stepStartTimeSeconds[step] * 1e6);
            out[oi++] = round5(mx);
            out[oi++] = round5(my);
            out[oi++] = round5(mz);
            var pulseStep = pulse.get(stepSegment[step]).steps().get(stepIndexInSegment[step]);
            out[oi++] = pulseStep.isRfOn() ? 1 : 0;

            double dt = stepDt[step];
            double e1 = Math.exp(-dt / t1);
            double e2 = Math.exp(-dt / t2);
            double[] iRow = transmitOnlyCoilI[step];
            double[] qRow = transmitOnlyCoilQ[step];
            // iRow already includes the STATIC-source contribution, so the total Bz at the
            // point is -b0Ref + Σ_c iRow[c]·ez[c]. (FieldSample.staticBz is the procedure
            // facing field — total static Bz folded in — so we don't read it here to avoid
            // double-counting the static drive.)
            double bx = 0, by = 0, bz = -b0Ref;
            for (int c = 0; c < nCoils; c++) {
                double ex = sample.coilEx()[c], ey = sample.coilEy()[c], ez = sample.coilEz()[c];
                bx += iRow[c] * ex - qRow[c] * ey;
                by += iRow[c] * ey + qRow[c] * ex;
                bz += iRow[c] * ez;
            }
            if (!pulseStep.isRfOn() && (bx * bx + by * by) < BlochStep.B_PERP_SQ_FLOOR) {
                var next = BlochStep.zOnly(bz, gamma, dt, e1, e2, mx, my, mz);
                mx = next.mx(); my = next.my(); mz = next.mz();
            } else {
                var next = BlochStep.rodrigues(bx, by, bz, gamma, dt, e1, e2, mx, my, mz);
                mx = next.mx(); my = next.my(); mz = next.mz();
            }
        }
        out[oi++] = round1(stepStartTimeSeconds[stepCount] * 1e6);
        out[oi++] = round5(mx);
        out[oi++] = round5(my);
        out[oi++] = round5(mz);
        out[oi] = 2;
        return new Trajectory(out);
    }

    public MagnetisationState singleSpinStateAt(Vec3 position, double tcMicros) {
        var traj = singleSpinTrajectory(position);
        return traj == null ? MagnetisationState.THERMAL_EQUILIBRIUM : traj.stepStateAt(tcMicros);
    }

    private static long trajectoryKey(Vec3 position) {
        long x = Double.doubleToLongBits(position.x());
        long y = Double.doubleToLongBits(position.y());
        long z = Double.doubleToLongBits(position.z());
        long h = x;
        h = 31 * h + y;
        h = 31 * h + z;
        return h;
    }

    /* ── Snapshot / restore ────────────────────────────────────────── */

    public SimulationSnapshot snapshot() {
        return new SimulationSnapshot(state, sCoilRePrev, sCoilImPrev, timeSeconds, currentStepIndex);
    }

    public void restore(SimulationSnapshot s) {
        System.arraycopy(s.state, 0, state, 0, fusedStateSize);
        System.arraycopy(s.sCoilRePrev, 0, sCoilRePrev, 0, sCoilRePrev.length);
        System.arraycopy(s.sCoilImPrev, 0, sCoilImPrev, 0, sCoilImPrev.length);
        timeSeconds = s.timeSeconds;
        currentStepIndex = s.stepIndex;
    }

    public void reset() {
        for (int s = 0; s < kernels.length; s++) {
            kernels[s].reset(state, stateOffset[s]);
        }
        Arrays.fill(sCoilRePrev, 0);
        Arrays.fill(sCoilImPrev, 0);
        timeSeconds = 0;
        currentStepIndex = 0;
        evaluator.resetHistory();
    }

    /* ── Helpers ─────────────────────────────────────────────────── */

    /**
     * The first {@link ContinuousMagnetisation} substance, or {@code null} when
     * no continuous magnetisation is in the FOV. UI surfaces that paint
     * substance-specific overlays (voxel heatmap, magnetisation arrows, the
     * geometry cross-section) gate on a non-null return value; per the
     * substance-aware UX plan they MUST NOT pretend a default substance
     * exists when the simulation has none.
     */
    public ContinuousMagnetisation primaryContinuousMagnetisation() {
        for (var s : substances) {
            if (s instanceof ContinuousMagnetisation cm) return cm;
        }
        return null;
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round5(double v) { return Math.round(v * 1e5) / 1e5; }

}
