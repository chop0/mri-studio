package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;

/** Helpers for presenting magnetisation in a movable isochromat-relative frame. */
public final class ReferenceFrameUtil {
    private static final double MIN_REFERENCE_MPERP = 1e-6;

    private ReferenceFrameUtil() {
    }

    public static MagnetisationState rotateIntoReferenceFrame(
        double mx,
        double my,
        double mz,
        Trajectory referenceTrajectory,
        int pointIndex,
        double tMicros
    ) {
        return rotateIntoReferenceFrame(new MagnetisationState(mx, my, mz), referenceStateAt(referenceTrajectory, pointIndex, tMicros));
    }

    public static MagnetisationState rotateIntoReferenceFrame(
        MagnetisationState state,
        Trajectory referenceTrajectory,
        double tMicros
    ) {
        return rotateIntoReferenceFrame(state, referenceStateAt(referenceTrajectory, -1, tMicros));
    }

    public static MagnetisationState rotateIntoReferenceFrame(MagnetisationState state, MagnetisationState referenceState) {
        if (state == null || referenceState == null || referenceState.mPerp() < MIN_REFERENCE_MPERP) {
            return state;
        }
        double scale = 1.0 / referenceState.mPerp();
        double cos = referenceState.mx() * scale;
        double sin = referenceState.my() * scale;
        return new MagnetisationState(
            state.mx() * cos + state.my() * sin,
            -state.mx() * sin + state.my() * cos,
            state.mz()
        );
    }

    public static double relativePhaseDeg(double phaseDeg, Trajectory referenceTrajectory, double tMicros) {
        return relativePhaseDeg(phaseDeg, referenceStateAt(referenceTrajectory, -1, tMicros));
    }

    public static double relativePhaseDeg(double phaseDeg, MagnetisationState referenceState) {
        if (referenceState == null || referenceState.mPerp() < MIN_REFERENCE_MPERP) {
            return normalizeDegrees(phaseDeg);
        }
        return normalizeDegrees(phaseDeg - referenceState.phaseDeg());
    }

    public static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        if (normalized < -180.0) normalized += 360.0;
        return normalized;
    }

    private static MagnetisationState referenceStateAt(Trajectory referenceTrajectory, int pointIndex, double tMicros) {
        if (referenceTrajectory == null) return null;
        if (pointIndex >= 0 && pointIndex < referenceTrajectory.pointCount()) {
            double sampleTime = referenceTrajectory.tAt(pointIndex);
            if (Math.abs(sampleTime - tMicros) <= 1e-6) {
                return new MagnetisationState(
                    referenceTrajectory.mxAt(pointIndex),
                    referenceTrajectory.myAt(pointIndex),
                    referenceTrajectory.mzAt(pointIndex)
                );
            }
        }
        return referenceTrajectory.interpolateAt(tMicros);
    }
}
