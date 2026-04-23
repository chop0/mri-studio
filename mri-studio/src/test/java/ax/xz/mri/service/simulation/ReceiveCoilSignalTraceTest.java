package ax.xz.mri.service.simulation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.ReceiveCoil;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for the M1 receive-coil signal pipeline. */
class ReceiveCoilSignalTraceTest {

    private static final double GAMMA = 267.522e6;
    private static final double DT = 1e-6;
    private static final double B1_MAX = 200e-6;

    private record Config(SimulationConfig simConfig, ProjectRepository repo, ProjectNodeId rfId) {}

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String name, String script) {
        var id = new ProjectNodeId("ef-" + name);
        repo.addEigenfield(new EigenfieldDocument(id, name, "", script, "T", 1.0));
        return id;
    }

    private static Config buildConfig(List<ReceiveCoil> receiveCoils) {
        var repo = ProjectRepository.untitled();
        var b0 = addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);");
        var rf = addEigenfield(repo, "RF", "return Vec3.of(1, 0, 0);");
        double b0Tesla = 0.0154;
        double larmorHz = GAMMA * b0Tesla / (2 * Math.PI);
        var fields = List.of(
            new FieldDefinition("B0", b0, AmplitudeKind.STATIC, 0, 0, b0Tesla),
            new FieldDefinition("RF", rf, AmplitudeKind.QUADRATURE, larmorHz, 0, B1_MAX)
        );
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA,
            5, 20, 10,
            3, 3,
            b0Tesla, DT,
            fields, receiveCoils);
        return new Config(cfg, repo, rf);
    }

    private static ReceiveCoil coilFromScript(ProjectRepository repo, String name, String script,
                                              double gain, double phaseDeg) {
        var id = addEigenfield(repo, "rx-" + name, script);
        return new ReceiveCoil(name, id, gain, phaseDeg);
    }

    /** 90°x pulse sized from Rabi rate, zero tail to let the signal appear after RF drops. */
    private static List<PulseSegment> ninetyXPulseThenFree(int nFree) {
        int n90 = (int) Math.round((Math.PI / 2) / (GAMMA * B1_MAX) / DT);
        var excitation = new PulseSegment(filledSteps(n90, B1_MAX, 0, 1.0));
        var tail = new PulseSegment(filledSteps(nFree, 0, 0, 0));
        return List.of(excitation, tail);
    }

    private static List<PulseStep> filledSteps(int count, double iAmp, double qAmp, double gate) {
        var out = new ArrayList<PulseStep>(count);
        for (int i = 0; i < count; i++) out.add(new PulseStep(new double[]{iAmp, qAmp}, gate));
        return out;
    }

    private static List<Segment> segmentsFor(List<PulseSegment> pulse) {
        var list = new ArrayList<Segment>();
        for (var seg : pulse) {
            int nPulse = 0, nFree = 0;
            for (var step : seg.steps()) {
                if (step.isRfOn()) nPulse++; else nFree++;
            }
            list.add(new Segment(DT, nFree, nPulse));
        }
        return list;
    }

    private static BlochData buildData(Config c, List<PulseSegment> pulse) {
        return BlochDataFactory.build(c.simConfig(), segmentsFor(pulse), c.repo());
    }

    @Test
    void uniformIsotropicCoilProducesMatchingRealAndImagComponents() {
        // A uniform (1,0,0) receive coil in the absence of phase offset emits
        //   s_real = Σ Mₓ,   s_imag = Σ M_y.
        // After 90°x on thermal equilibrium the convention gives My ≈ -1,
        // so we should see a negative imag and ≈0 real after excitation.
        var repo = ProjectRepository.untitled();
        var rxId = addEigenfield(repo, "rx-iso", "return Vec3.of(1, 0, 0);");
        var coil = new ReceiveCoil("Iso", rxId, 1.0, 0.0);
        var c = buildConfig(List.of(coil));
        // Re-wire the config so it uses the same repository instance as the coil.
        var rebuiltRepo = repo;
        rebuiltRepo.addEigenfield((EigenfieldDocument) c.repo().node(c.rfId()));
        rebuiltRepo.addEigenfield((EigenfieldDocument) c.repo().node(c.simConfig().fields().get(0).eigenfieldId()));

        var cfg = c.simConfig().withReceiveCoils(List.of(coil));
        var pulse = ninetyXPulseThenFree(500);
        var data = BlochDataFactory.build(cfg, segmentsFor(pulse), rebuiltRepo);

        var trace = SignalTraceComputer.compute(data, pulse);
        assertNotNull(trace);
        assertTrue(trace.points().size() > 1);

        var last = trace.points().getLast();
        assertTrue(Math.abs(last.imag()) > 0.5 * c.simConfig().nR() * c.simConfig().nZ() * 0.3,
            "Expected non-trivial imag component after 90° pulse, got " + last);
    }

    @Test
    void phaseNinetyDegreesSwapsRealAndImag() {
        // Coil with phaseDeg=0: s = sr + i·si.
        // Coil with phaseDeg=90: s = i·(sr + i·si) = -si + i·sr.
        // So real(90°) = -imag(0°) and imag(90°) = real(0°).
        var repo = ProjectRepository.untitled();
        addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);");
        addEigenfield(repo, "RF", "return Vec3.of(1, 0, 0);");
        var rxId = addEigenfield(repo, "rx-iso", "return Vec3.of(1, 0, 0);");

        var coilZero = new ReceiveCoil("zero", rxId, 1.0, 0.0);
        var coilNinety = new ReceiveCoil("ninety", rxId, 1.0, 90.0);
        double b0Tesla = 0.0154;
        double larmorHz = GAMMA * b0Tesla / (2 * Math.PI);
        var fields = List.of(
            new FieldDefinition("B0", new ProjectNodeId("ef-B0"), AmplitudeKind.STATIC, 0, 0, b0Tesla),
            new FieldDefinition("RF", new ProjectNodeId("ef-RF"), AmplitudeKind.QUADRATURE, larmorHz, 0, B1_MAX)
        );
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, b0Tesla, DT,
            fields, List.of(coilZero, coilNinety));
        var pulse = ninetyXPulseThenFree(50);
        var data = BlochDataFactory.build(cfg, segmentsFor(pulse), repo);
        var traces = SignalTraceComputer.computeAll(data, pulse);

        var zero = traces.byCoil().get("zero").points();
        var ninety = traces.byCoil().get("ninety").points();
        assertEquals(zero.size(), ninety.size());
        for (int i = 0; i < zero.size(); i++) {
            assertEquals(-zero.get(i).imag(), ninety.get(i).real(), 1e-9);
            assertEquals(zero.get(i).real(), ninety.get(i).imag(), 1e-9);
        }
    }

    @Test
    void multiCoilTracesAreIndependent() {
        var repo = ProjectRepository.untitled();
        addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);");
        addEigenfield(repo, "RF", "return Vec3.of(1, 0, 0);");
        var rxX = addEigenfield(repo, "rx-x", "return Vec3.of(1, 0, 0);");
        var rxY = addEigenfield(repo, "rx-y", "return Vec3.of(0, 1, 0);");

        var coilX = new ReceiveCoil("X", rxX, 1.0, 0.0);
        var coilY = new ReceiveCoil("Y", rxY, 1.0, 0.0);
        double b0Tesla = 0.0154;
        double larmorHz = GAMMA * b0Tesla / (2 * Math.PI);
        var fields = List.of(
            new FieldDefinition("B0", new ProjectNodeId("ef-B0"), AmplitudeKind.STATIC, 0, 0, b0Tesla),
            new FieldDefinition("RF", new ProjectNodeId("ef-RF"), AmplitudeKind.QUADRATURE, larmorHz, 0, B1_MAX)
        );
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, b0Tesla, DT,
            fields, List.of(coilX, coilY));
        var pulse = ninetyXPulseThenFree(50);
        var data = BlochDataFactory.build(cfg, segmentsFor(pulse), repo);

        var traces = SignalTraceComputer.computeAll(data, pulse);
        assertEquals(2, traces.byCoil().size());
        assertEquals("X", traces.primaryCoilName());

        // For coil X (E = (1,0,0)):  s_real = Σ Mₓ,  s_imag = Σ M_y.
        // For coil Y (E = (0,1,0)):  s_real = Σ M_y, s_imag = -Σ Mₓ.
        // Hence coilY.real = coilX.imag and coilY.imag = -coilX.real at every step.
        var xs = traces.byCoil().get("X").points();
        var ys = traces.byCoil().get("Y").points();
        for (int i = 0; i < xs.size(); i++) {
            assertEquals(xs.get(i).imag(), ys.get(i).real(), 1e-9);
            assertEquals(-xs.get(i).real(), ys.get(i).imag(), 1e-9);
        }
    }

    @Test
    void emptyReceiveCoilsYieldNullPrimaryTrace() {
        var repo = ProjectRepository.untitled();
        addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);");
        addEigenfield(repo, "RF", "return Vec3.of(1, 0, 0);");
        double b0Tesla = 0.0154;
        double larmorHz = GAMMA * b0Tesla / (2 * Math.PI);
        var fields = List.of(
            new FieldDefinition("B0", new ProjectNodeId("ef-B0"), AmplitudeKind.STATIC, 0, 0, b0Tesla),
            new FieldDefinition("RF", new ProjectNodeId("ef-RF"), AmplitudeKind.QUADRATURE, larmorHz, 0, B1_MAX)
        );
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, b0Tesla, DT,
            fields, List.of());
        var pulse = ninetyXPulseThenFree(10);
        var data = BlochDataFactory.build(cfg, segmentsFor(pulse), repo);

        var trace = SignalTraceComputer.compute(data, pulse);
        assertNull(trace);
        assertTrue(SignalTraceComputer.computeAll(data, pulse).byCoil().isEmpty());
    }

    @Test
    void gainScalesBothRealAndImag() {
        var repo = ProjectRepository.untitled();
        addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);");
        addEigenfield(repo, "RF", "return Vec3.of(1, 0, 0);");
        var rxId = addEigenfield(repo, "rx-iso", "return Vec3.of(1, 0, 0);");

        var coil1 = new ReceiveCoil("one", rxId, 1.0, 0.0);
        var coil2 = new ReceiveCoil("two", rxId, 2.5, 0.0);
        double b0Tesla = 0.0154;
        double larmorHz = GAMMA * b0Tesla / (2 * Math.PI);
        var fields = List.of(
            new FieldDefinition("B0", new ProjectNodeId("ef-B0"), AmplitudeKind.STATIC, 0, 0, b0Tesla),
            new FieldDefinition("RF", new ProjectNodeId("ef-RF"), AmplitudeKind.QUADRATURE, larmorHz, 0, B1_MAX)
        );
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, b0Tesla, DT,
            fields, List.of(coil1, coil2));
        var pulse = ninetyXPulseThenFree(50);
        var data = BlochDataFactory.build(cfg, segmentsFor(pulse), repo);

        var traces = SignalTraceComputer.computeAll(data, pulse);
        var ones = traces.byCoil().get("one").points();
        var twos = traces.byCoil().get("two").points();
        for (int i = 0; i < ones.size(); i++) {
            assertEquals(2.5 * ones.get(i).real(), twos.get(i).real(), 1e-9);
            assertEquals(2.5 * ones.get(i).imag(), twos.get(i).imag(), 1e-9);
        }
    }
}
