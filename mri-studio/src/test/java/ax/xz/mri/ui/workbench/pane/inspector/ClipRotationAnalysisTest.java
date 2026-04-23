package ax.xz.mri.ui.workbench.pane.inspector;

import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClipRotationAnalysis}, the helper that pulls an axis-angle
 * description out of a pair of magnetisation states.
 *
 * <p>The tests cover the textbook MRI rotations: 90°x maps +z → −y, 90°y maps
 * +z → +x, 180° inversions, plus the degenerate parallel/anti-parallel cases.
 */
class ClipRotationAnalysisTest {

    private static final double ANGLE_TOL = 1e-9;
    private static final double AXIS_TOL = 1e-9;

    @Test
    void ninety_x_rotation_fromZ_toMinusY() {
        // A 90°x pulse on thermal equilibrium takes (0,0,1) to (0,-1,0).
        var before = MagnetisationState.THERMAL_EQUILIBRIUM;
        var after = new MagnetisationState(0, -1, 0);

        var r = ClipRotationAnalysis.between(before, after);

        assertEquals(Math.PI / 2, r.angleRadians(), ANGLE_TOL);
        assertEquals(90.0, r.angleDegrees(), 1e-9);
        assertEquals(1.0, r.axisX(), AXIS_TOL);
        assertEquals(0.0, r.axisY(), AXIS_TOL);
        assertEquals(0.0, r.axisZ(), AXIS_TOL);
    }

    @Test
    void ninety_y_rotation_fromZ_toPlusX() {
        // A 90°y pulse on thermal equilibrium takes (0,0,1) to (+1,0,0).
        var before = MagnetisationState.THERMAL_EQUILIBRIUM;
        var after = new MagnetisationState(1, 0, 0);

        var r = ClipRotationAnalysis.between(before, after);

        assertEquals(Math.PI / 2, r.angleRadians(), ANGLE_TOL);
        // Axis should be −y (right-hand rule: (0,0,1) × (1,0,0) = (0,1,0)·(−1))
        // Wait: (0,0,1) × (1,0,0) = (0·0−1·0, 1·1−0·0, 0·0−0·1) = (0,1,0). So +y.
        assertEquals(0.0, r.axisX(), AXIS_TOL);
        assertEquals(1.0, r.axisY(), AXIS_TOL);
        assertEquals(0.0, r.axisZ(), AXIS_TOL);
    }

    @Test
    void one_eighty_about_x_fromZ_toMinusZ() {
        // A 180°x pulse on +z gives −z. The axis should be +x.
        var before = MagnetisationState.THERMAL_EQUILIBRIUM;
        var after = new MagnetisationState(0, 0, -1);

        var r = ClipRotationAnalysis.between(before, after);

        assertEquals(Math.PI, r.angleRadians(), 1e-9);
        // Anti-parallel case: the axis is underdetermined; the analysis picks a
        // global basis perpendicular to `before`. For before=+z, the fallback
        // picks x. Any axis in the xy-plane is correct; we just check that it's
        // perpendicular to z.
        assertEquals(0.0, r.axisZ(), AXIS_TOL, "inversion axis must be perpendicular to ±z");
        assertEquals(1.0,
            r.axisX() * r.axisX() + r.axisY() * r.axisY() + r.axisZ() * r.axisZ(),
            1e-9, "axis must be unit length");
    }

    @Test
    void parallel_vectors_reportZeroRotation() {
        var state = new MagnetisationState(0, 0, 1);
        var r = ClipRotationAnalysis.between(state, state);
        assertEquals(0.0, r.angleRadians(), ANGLE_TOL);
        // Axis is formally undefined; just require it's finite and unit-ish.
        double len = Math.sqrt(r.axisX() * r.axisX() + r.axisY() * r.axisY() + r.axisZ() * r.axisZ());
        assertTrue(Math.abs(len - 1.0) < 1e-6 || Math.abs(len) < 1e-6,
            "placeholder axis should be unit length or zero, got " + len);
    }

    @Test
    void nearly_identical_states_report_smallAngle() {
        var before = new MagnetisationState(0, 0, 1);
        // Tilt by ~1° about x: (0, -sin(1°), cos(1°))
        double eps = Math.toRadians(1);
        var after = new MagnetisationState(0, -Math.sin(eps), Math.cos(eps));
        var r = ClipRotationAnalysis.between(before, after);
        assertEquals(eps, r.angleRadians(), 1e-9);
        assertEquals(1.0, r.axisX(), 1e-6);
    }

    @Test
    void zeroVectorInput_returnsDegenerateRotation() {
        var before = new MagnetisationState(0, 0, 0);
        var after = new MagnetisationState(1, 0, 0);
        var r = ClipRotationAnalysis.between(before, after);
        // We don't promise a specific axis, but the angle should be 0 (treated
        // as degenerate) and the rotation should be finite everywhere.
        assertEquals(0, r.angleRadians());
        assertTrue(Double.isFinite(r.axisX()) && Double.isFinite(r.axisY()) && Double.isFinite(r.axisZ()));
    }

    @Test
    void normalisesLengths_whenInputsAreNotUnit() {
        // Stretched inputs — the axis should still read as +x and angle 90°.
        var before = new MagnetisationState(0, 0, 2);
        var after = new MagnetisationState(0, -3, 0);
        var r = ClipRotationAnalysis.between(before, after);
        assertEquals(Math.PI / 2, r.angleRadians(), 1e-9);
        assertEquals(1.0, r.axisX(), 1e-9);
    }

    @Test
    void ofClip_returnsNullForMissingTrajectory() {
        assertNull(ClipRotationAnalysis.ofClip(null, 0, 100));
    }

    @Test
    void ofClip_returnsNullForSinglePointTrajectory() {
        var data = new double[]{0, 0, 0, 1, 0};
        var traj = new Trajectory(data);
        assertNull(ClipRotationAnalysis.ofClip(traj, 0, 100));
    }

    @Test
    void ofClip_readsStatesAtClipBoundaries() {
        // Build a trajectory with three samples: t=0 at +z, t=100 at −y, t=200 at −z.
        // A clip spanning [0,100] should read the 90°x rotation.
        double[] data = {
            0,   0,  0,  1, 0,
            100, 0, -1,  0, 1,
            200, 0,  0, -1, 0
        };
        var traj = new Trajectory(data);
        var r = ClipRotationAnalysis.ofClip(traj, 0, 100);
        assertNotNull(r);
        assertEquals(Math.PI / 2, r.angleRadians(), 1e-6);
        assertEquals(1.0, r.axisX(), 1e-6);
        assertEquals(0.0, r.axisY(), 1e-6);
        assertEquals(0.0, r.axisZ(), 1e-6);
    }
}
