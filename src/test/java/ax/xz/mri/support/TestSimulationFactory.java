package ax.xz.mri.support;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.state.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * Small deterministic Bloch simulations used by unit tests.
 *
 * <p>Channel layout: controls = {@code [rf_I, rf_Q, gx, gz]}. The test
 * circuit has three drive sources (RF QUADRATURE, Gx REAL, Gz REAL), three
 * coils (each with a scripted eigenfield shape), and one probe wired to the
 * RF coil for receive. Construction goes through {@link CompiledSimulation
 * #compile} so tests exercise production code paths end-to-end.
 *
 * <p>Spatial layout (extent + resolution) lives on the substance — the
 * default proton magnetisation here is sized for the test circuit's
 * cylindrical (r,z) sampling: ±30 mm × ±30 mm × ±10 mm at 3 × 3 × 3 voxels.
 */
public final class TestSimulationFactory {
    private TestSimulationFactory() {}

    /** Default test substance: proton water with the test grid (±30 mm × ±30 mm × ±10 mm, 3 × 3 × 3). */
    private static ContinuousMagnetisation testProton() {
        return new ContinuousMagnetisation(
            1.0, 0.08, 267.5e6, 1.0,
            0.030, 0.030, 0.010,
            3, 3, 3);
    }

    private static PulseStep step(double b1x, double b1y, double gx, double gz, double rfGate) {
        return new PulseStep(new double[]{b1x, b1y, gx, gz}, rfGate);
    }

    /** Default test simulation: 3×3 cylindrical grid, two-segment pulse-shaped sequence. */
    public static CompiledSimulation sampleSimulation() {
        return compile(testSegments(), pulseA(), 1.5);
    }

    /** Test simulation baked with {@link #pulseB()} for variation-detection tests. */
    public static CompiledSimulation sampleSimulationWithPulseB() {
        return compile(testSegments(), pulseB(), 1.5);
    }

    /** Sample with the segment list intentionally empty — exercises null-segment guards. */
    public static CompiledSimulation brokenSimulationMissingSegments() {
        return compile(List.of(), List.of(), 1.5);
    }

    /** Build a simulation with an arbitrary segments + pulse pair on the test circuit. */
    public static CompiledSimulation simulationWith(List<Segment> segments, List<PulseSegment> pulse) {
        return compile(segments, pulse, 1.5);
    }

    public static List<PulseSegment> pulseA() {
        return List.of(
            new PulseSegment(List.of(
                step(1.0e-6, 0, 0, 0, 1.0),
                step(1.0e-6, 0, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                step(0, 0, 0, 0.010, 0),
                step(0, 0, 0, -0.010, 0)
            ))
        );
    }

    public static List<PulseSegment> pulseB() {
        return List.of(
            new PulseSegment(List.of(
                step(2.0e-6, 0, 0, 0, 1.0),
                step(0.5e-6, 0.5e-6, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                step(0, 0, 0.008, 0.018, 0),
                step(0, 0, -0.008, -0.018, 0)
            ))
        );
    }

    /**
     * Simulation whose final state has spatially incoherent transverse
     * magnetisation: a hard 90°_x pulse followed by a gradient-driven dephase
     * pass. Different z-positions accumulate different phases, so the
     * coherent ensemble direction does not align with each per-spin moment —
     * exactly what the geometry-shading service exercises when distinguishing
     * |M⊥| from the projection along the coherent receive axis.
     */
    public static CompiledSimulation incoherentTransverseSimulation() {
        var excite = new PulseSegment(List.of(step(5.873e-3, 0, 0, 0, 1.0)));
        var dephase = new ArrayList<PulseStep>();
        for (int i = 0; i < 50; i++) dephase.add(step(0, 0, 0, 1.0, 0));
        return compile(
            List.of(new Segment(1.0e-6, 0, 1), new Segment(1.0e-6, 50, 0)),
            List.of(excite, new PulseSegment(dephase)),
            1.5);
    }

    public static List<PulseSegment> freePrecessionPulse() {
        var steps = new ArrayList<PulseStep>();
        steps.add(step(5.873e-3, 0, 0, 0, 1.0));
        var dephase = new ArrayList<PulseStep>();
        for (int i = 0; i < 50; i++) dephase.add(step(0, 0, 0, 1.0, 0));
        return List.of(new PulseSegment(steps), new PulseSegment(dephase));
    }

    private static List<Segment> testSegments() {
        return List.of(
            new Segment(1.0e-6, 0, 2),
            new Segment(1.0e-6, 2, 0)
        );
    }

    /**
     * Build a self-contained compiled simulation from the supplied
     * {@code segments + pulse + b0Ref}. Substance is the default
     * proton {@link ContinuousMagnetisation} (which owns its own spatial
     * extent + resolution); circuit is the canonical test three-coil setup
     * wired through {@link CompiledSimulation#compile}.
     */
    private static CompiledSimulation compile(List<Segment> segments, List<PulseSegment> pulse, double b0Ref) {
        var rfEf = makeEigenfield("ef-rf", "Rf",     "return Vec3.of(1, 0, 0);");
        var gxEf = makeEigenfield("ef-gx", "GxField", "return Vec3.of(0, 0, x);");
        var gzEf = makeEigenfield("ef-gz", "GzField", "return Vec3.of(0, 0, z);");
        var repo = ProjectState.empty()
            .withEigenfield(rfEf).withEigenfield(gxEf).withEigenfield(gzEf);

        var rfISrc = new CircuitComponent.VoltageSource(new ComponentId("src-rf-i"),
            "RF I", AmplitudeKind.REAL, 0, 0, 1, 0);
        var rfQSrc = new CircuitComponent.VoltageSource(new ComponentId("src-rf-q"),
            "RF Q", AmplitudeKind.REAL, 0, 0, 1, 0);
        var gxSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gx"),
            "Gx", AmplitudeKind.REAL, 0, -1, 1, 0);
        var gzSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gz"),
            "Gz", AmplitudeKind.REAL, 0, -1, 1, 0);
        var rfModulator = new CircuitComponent.Modulator(new ComponentId("mod-rf"),
            "RF Mod", 0);
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil", rfEf.id(), 0, 1);
        var gxCoil = new CircuitComponent.Coil(new ComponentId("coil-gx"), "Gx Coil", gxEf.id(), 0, 1);
        var gzCoil = new CircuitComponent.Coil(new ComponentId("coil-gz"), "Gz Coil", gzEf.id(), 0, 1);
        var probe = new CircuitComponent.Probe(new ComponentId("probe-rx"),
            "Primary RX", 1.0, 0.0, Double.POSITIVE_INFINITY);

        var wires = List.of(
            new Wire("w-rfi", new ComponentTerminal(rfISrc.id(), "out"), new ComponentTerminal(rfModulator.id(), "in0")),
            new Wire("w-rfq", new ComponentTerminal(rfQSrc.id(), "out"), new ComponentTerminal(rfModulator.id(), "in1")),
            new Wire("w-rf", new ComponentTerminal(rfModulator.id(), "out"), new ComponentTerminal(rfCoil.id(), "in")),
            new Wire("w-gx", new ComponentTerminal(gxSrc.id(), "out"), new ComponentTerminal(gxCoil.id(), "in")),
            new Wire("w-gz", new ComponentTerminal(gzSrc.id(), "out"), new ComponentTerminal(gzCoil.id(), "in")),
            new Wire("w-probe", new ComponentTerminal(probe.id(), "in"), new ComponentTerminal(rfCoil.id(), "in"))
        );
        var doc = new CircuitDocument(new ProjectNodeId("circuit-test"), "Test",
            List.of(rfISrc, rfQSrc, gxSrc, gzSrc, rfModulator, rfCoil, gxCoil, gzCoil, probe),
            wires, CircuitLayout.empty());

        List<Substance> substances = List.of(testProton());
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(
            doc, repo, substances, segments, pulse, b0Ref));
    }

    /**
     * Convenience helper that wraps a single {@code return} statement into the
     * minimal full-class form the {@link ax.xz.mri.dsl.EigenfieldEngine} expects.
     * The {@code className} must be a valid Java identifier; the {@code body}
     * is inlined verbatim into the generated {@code evaluate} method.
     */
    private static EigenfieldDocument makeEigenfield(String id, String className, String body) {
        String script = "import module ax.xz.mri;\n"
                      + "class " + className + " implements EigenfieldScript {\n"
                      + "    public Vec3 evaluate(double x, double y, double z) {\n"
                      + "        " + body + "\n"
                      + "    }\n"
                      + "}\n";
        return new EigenfieldDocument(new ProjectNodeId(id), id, "", script, "T");
    }
}
