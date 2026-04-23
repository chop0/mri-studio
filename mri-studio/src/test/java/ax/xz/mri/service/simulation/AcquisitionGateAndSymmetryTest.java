package ax.xz.mri.service.simulation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.DrivePath;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.ReceiveCoil;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.TransmitCoil;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for M3 (gate signals + acquisition windows) and the adaptive-grid groundwork. */
class AcquisitionGateAndSymmetryTest {

    private static final double GAMMA = 267.522e6;
    private static final double DT = 1e-6;
    private static final double B0 = 0.0154;

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String name, String script, FieldSymmetry sym) {
        var id = new ProjectNodeId("ef-" + name);
        repo.addEigenfield(new EigenfieldDocument(id, name, "", script, "T", 1.0, sym));
        return id;
    }

    @Test
    void acquisitionGateMutesReceiveSignalWhenLow() {
        var repo = ProjectRepository.untitled();
        var b0Id = addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);", FieldSymmetry.AXISYMMETRIC_Z);
        var rxId = addEigenfield(repo, "rx", "return Vec3.of(1, 0, 0);", FieldSymmetry.AXISYMMETRIC_Z);

        var coils = List.of(new TransmitCoil("B0 Coil", b0Id, 0));
        // B0 static (0 channels) + RX Gate (1 channel) = controls[0] is the gate.
        var paths = List.of(
            new DrivePath("B0", "B0 Coil", AmplitudeKind.STATIC, 0, 0, B0, null),
            new DrivePath("RX Gate", null, AmplitudeKind.GATE, 0, 0, 1, null)
        );
        var rx = new ReceiveCoil("Iso", rxId, 1.0, 0.0, 0.0, "RX Gate");
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT,
            coils, paths, List.of(rx));

        // Two segments: gate open (1.0) for first half, gate closed (0.0) for second.
        // With M_xy nonzero initially (we seed via mx0) we should see signal → 0 when gate drops.
        var firstHalf = new PulseSegment(filled(10, 1.0));
        var secondHalf = new PulseSegment(filled(10, 0.0));
        var pulse = List.of(firstHalf, secondHalf);
        var segments = List.of(
            new Segment(DT, 10, 0),
            new Segment(DT, 10, 0)
        );

        // Manually seed transverse magnetisation at all grid points.
        var data = BlochDataFactory.build(cfg, segments, repo);
        var f = data.field();
        for (int r = 0; r < f.mx0.length; r++) {
            for (int z = 0; z < f.mx0[r].length; z++) {
                f.mx0[r][z] = 1.0;
                f.my0[r][z] = 0.0;
                f.mz0[r][z] = 0.0;
            }
        }

        var trace = SignalTraceComputer.compute(data, pulse);
        assertEquals(21, trace.points().size(), "one initial sample + one per step");

        // Sample at step 5 (gate open): non-zero.
        assertTrue(trace.points().get(5).signal() > 0.1,
            "Gate open: expected non-trivial signal, got " + trace.points().get(5));

        // Sample at step 15 (gate closed): zero.
        assertEquals(0.0, trace.points().get(15).signal(), 1e-12,
            "Gate closed: signal must be exactly zero");
    }

    @Test
    void factoryRejectsCartesian3dEigenfieldUntilBackendExists() {
        var repo = ProjectRepository.untitled();
        var b0Id = addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);", FieldSymmetry.CARTESIAN_3D);
        var coils = List.of(new TransmitCoil("B0 Coil", b0Id, 0));
        var paths = List.of(new DrivePath("B0", "B0 Coil", AmplitudeKind.STATIC, 0, 0, B0, null));
        var cfg = new SimulationConfig(
            1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT,
            coils, paths, List.of());

        var ex = assertThrows(IllegalStateException.class,
            () -> BlochDataFactory.build(cfg, List.of(new Segment(DT, 1, 0)), repo));
        assertTrue(ex.getMessage().contains("CARTESIAN_3D"),
            "Error should mention the declared symmetry: " + ex.getMessage());
    }

    @Test
    void drivePathWithGateInputNameRejectsUnknownGate() {
        var paths = List.of(
            new DrivePath("Drive", "Coil", AmplitudeKind.REAL, 0, -1, 1, "Nonexistent Gate")
        );
        var coils = List.of(new TransmitCoil("Coil", new ProjectNodeId("x"), 0));
        var ex = assertThrows(IllegalArgumentException.class,
            () -> new SimulationConfig(
                1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT,
                coils, paths, List.of()));
        assertTrue(ex.getMessage().contains("gate-input"),
            "Config validation must flag the missing gate reference: " + ex.getMessage());
    }

    private static List<PulseStep> filled(int count, double gateValue) {
        var out = new ArrayList<PulseStep>(count);
        for (int i = 0; i < count; i++) {
            out.add(new PulseStep(new double[]{gateValue}, 0.0));
        }
        return out;
    }
}
