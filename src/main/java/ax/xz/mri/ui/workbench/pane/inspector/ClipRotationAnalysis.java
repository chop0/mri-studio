package ax.xz.mri.ui.workbench.pane.inspector;

import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.util.MathUtil;

/**
 * Extracts the net rotation an RF clip applies to a point's magnetisation,
 * by comparing the magnetisation state at the clip's start and end as
 * sampled from the point's simulated {@link Trajectory}.
 *
 * <p>The extraction is the axis–angle decomposition of the shortest rotation
 * that takes the {@code before} direction onto the {@code after} direction.
 * In a pure-rotation regime (T₁/T₂ negligible over the clip window, and the
 * magnetisation pre-clip lies roughly on the unit sphere) this matches the
 * rotation the Bloch equation actually applied. Under heavy relaxation the
 * reported angle absorbs the inevitable length change; we normalise the
 * vectors before taking the dot product, so the reported axis/angle is the
 * direction-space rotation.
 *
 * <h3>Degenerate cases</h3>
 * <ul>
 *   <li>Either vector has near-zero magnitude → {@link #zero zero rotation}.</li>
 *   <li>Vectors parallel (angle ≈ 0) → rotation angle 0, axis returned as the
 *       normalised {@code before} direction (it's formally undefined but this
 *       gives a sensible, stable placeholder for display).</li>
 *   <li>Vectors anti-parallel (angle ≈ π) → axis chosen perpendicular to
 *       {@code before} along the most-orthogonal global basis direction.</li>
 * </ul>
 */
public record ClipRotationAnalysis(
    MagnetisationState before,
    MagnetisationState after,
    double angleRadians,
    double axisX,
    double axisY,
    double axisZ
) {

    public double angleDegrees() {
        return angleRadians * 180.0 / Math.PI;
    }

    public static ClipRotationAnalysis zero() {
        return new ClipRotationAnalysis(
            MagnetisationState.THERMAL_EQUILIBRIUM,
            MagnetisationState.THERMAL_EQUILIBRIUM,
            0, 0, 0, 1);
    }

    /**
     * Compute the rotation applied by a clip window on a trajectory. Returns
     * {@code null} if the trajectory is missing or too short to sample.
     */
    public static ClipRotationAnalysis ofClip(Trajectory trajectory,
                                              double clipStartMicros,
                                              double clipEndMicros) {
        if (trajectory == null || trajectory.pointCount() < 2) return null;
        var before = trajectory.interpolateAt(clipStartMicros);
        var after = trajectory.interpolateAt(clipEndMicros);
        if (before == null || after == null) return null;
        return between(before, after);
    }

    /** Build the rotation description taking {@code before} onto {@code after}. */
    public static ClipRotationAnalysis between(MagnetisationState before, MagnetisationState after) {
        if (before == null || after == null) return null;

        double bMag = Math.sqrt(before.mx() * before.mx() + before.my() * before.my() + before.mz() * before.mz());
        double aMag = Math.sqrt(after.mx() * after.mx() + after.my() * after.my() + after.mz() * after.mz());
        if (bMag < 1e-12 || aMag < 1e-12) {
            return new ClipRotationAnalysis(before, after, 0, 0, 0, 1);
        }

        double bx = before.mx() / bMag, by = before.my() / bMag, bz = before.mz() / bMag;
        double ax = after.mx() / aMag, ay = after.my() / aMag, az = after.mz() / aMag;

        double cos = MathUtil.clampUnit(bx * ax + by * ay + bz * az);
        double angle = Math.acos(cos);

        // axis = before × after (right-hand rule rotates before onto after)
        double nx = by * az - bz * ay;
        double ny = bz * ax - bx * az;
        double nz = bx * ay - by * ax;
        double nMag = Math.sqrt(nx * nx + ny * ny + nz * nz);

        if (nMag < 1e-9) {
            if (cos > 0) {
                return new ClipRotationAnalysis(before, after, 0, bx, by, bz);
            }
            // Anti-parallel: pick an axis perpendicular to `before`. Choose the
            // global basis direction with which `before` has the smallest
            // absolute component, so the cross product stays numerically stable.
            double absBx = Math.abs(bx), absBy = Math.abs(by), absBz = Math.abs(bz);
            double px, py, pz;
            if (absBx <= absBy && absBx <= absBz) {
                px = 1; py = 0; pz = 0;
            } else if (absBy <= absBz) {
                px = 0; py = 1; pz = 0;
            } else {
                px = 0; py = 0; pz = 1;
            }
            nx = by * pz - bz * py;
            ny = bz * px - bx * pz;
            nz = bx * py - by * px;
            nMag = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nMag < 1e-12) {
                return new ClipRotationAnalysis(before, after, angle, 1, 0, 0);
            }
        }
        return new ClipRotationAnalysis(before, after, angle, nx / nMag, ny / nMag, nz / nMag);
    }
}
