package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.field.CartesianGrid;
import ax.xz.mri.model.field.CylindricalGrid;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.support.EigenfieldScripts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link CompiledSimulation} 3-D Cartesian path against an
 * analytical reference: a uniform transverse {@code B1} on a non-axisymmetric
 * FOV produces a clean Rabi rotation at every point.
 *
 * <p>The test also asserts the symmetry-pick logic: when any eigenfield in
 * the circuit declares {@link FieldSymmetry#CARTESIAN_3D}, the compiled grid
 * is a {@link CartesianGrid}; otherwise the all-axisymmetric setup gets the
 * cylindrical fast path.
 */
final class CartesianSimulatorTest {

    @Test
    void cylindricalGridIsChosenWhenEverythingIsAxisymmetric() {
        var sim = build("return Vec3.of(1, 0, 0);", FieldSymmetry.AXISYMMETRIC_Z);
        assertInstanceOf(CylindricalGrid.class, sim.grid(),
            "All-axisymmetric setup should use the cylindrical fast path");
    }

    @Test
    void cartesianGridIsChosenWhenAnyEigenfieldIsCartesian() {
        // A Cartesian eigenfield (one of the coils carries it) forces the
        // simulator off the cylindrical fast path.
        var sim = build("return Vec3.of(1, 0, 0);", FieldSymmetry.CARTESIAN_3D);
        assertInstanceOf(CartesianGrid.class, sim.grid(),
            "Cartesian eigenfield must escalate the grid to CartesianGrid");
    }

    @Test
    void rabiRotationMatchesAnalyticalOnCartesianGrid() {
        // 90° pulse: γ * B1 * dt = π/2 with γ = 267.5e6, dt = 1 µs ⇒ B1 = 5.87325e-3 T.
        // Cartesian-declared uniform B1 eigenfield — produces an actual flip across
        // the volume (not the x-scaled tiny-rotation case above).
        var sim = build("return Vec3.of(1, 0, 0);", FieldSymmetry.CARTESIAN_3D);
        var traj = sim.singleSpinTrajectory(new Vec3(5.0e-3, 3.0e-3, 0.0));
        assertNotNull(traj);
        var endIdx = traj.pointCount() - 1;
        double mxEnd = traj.mxAt(endIdx);
        double myEnd = traj.myAt(endIdx);
        double mzEnd = traj.mzAt(endIdx);
        assertEquals(0.0, mxEnd, 1.0e-3, "After 90°_x: mx ≈ 0");
        assertEquals(-1.0, myEnd, 1.0e-3, "After 90°_x: my ≈ -1");
        assertEquals(0.0, mzEnd, 1.0e-3, "After 90°_x: mz ≈ 0");
    }

    /**
     * Build a self-contained Cartesian simulation with one coil whose
     * eigenfield is described by {@code coilScript} (declaring
     * {@code coilSymmetry}). Substance extent is a 10 mm half-extent cube
     * at 7×7×7 voxels — large enough to assert grid type and run a Rabi pulse.
     */
    private static CompiledSimulation build(String coilScript, FieldSymmetry coilSymmetry) {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "rf", "",
            EigenfieldScripts.wrap(coilScript), "T", coilSymmetry);
        var repo = ProjectState.empty().withEigenfield(ef);

        var src = new CircuitComponent.VoltageSource(new ComponentId("src"),
            "RF", AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", ef.id(), 0, 1);
        var wires = List.of(
            new Wire("w", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "in"))
        );
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.of(src, coil), wires, CircuitLayout.empty());

        // 90°_x at γ_proton in 1 µs: B1 = π/(2γ·dt) ≈ 5.873e-3 T.
        var pulse = List.of(new PulseSegment(List.of(
            new PulseStep(new double[]{5.873e-3}, 1.0)
        )));
        var segments = List.of(new Segment(1.0e-6, 0, 1));

        var proton = new ContinuousMagnetisation(
            1.0, 0.1, 267.5e6, 1.0,
            0.010, 0.010, 0.010,
            7, 7, 7);
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(
            doc, repo, List.of(proton), segments, pulse, 0.0));
    }
}
