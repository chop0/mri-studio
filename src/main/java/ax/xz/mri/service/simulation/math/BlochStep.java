package ax.xz.mri.service.simulation.math;

import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.simulation.MagnetisationState;

/**
 * One Bloch step: Rodrigues rotation about the current B vector plus
 * exponential T2 (transverse) and T1 (longitudinal) decay.
 *
 * <p>Shared by {@link ax.xz.mri.service.simulation.BlochSimulator} (per-point
 * scalar form) and {@link ax.xz.mri.optimisation.BlochObjectiveEngine}
 * (vector-of-points form). Both engines need byte-identical numerics.
 */
public final class BlochStep {
    private BlochStep() {}

    /** Threshold below which {@code |B_perp|²} is treated as zero — triggers the z-only short-circuit. */
    public static final double B_PERP_SQ_FLOOR = 1e-30;

    /**
     * Full Rodrigues update plus T1/T2 decay. Use when {@code |B_perp|²} is
     * non-negligible.
     */
    public static MagnetisationState rodrigues(
        double bx, double by, double bz,
        double gamma, double dt,
        double e1, double e2,
        double mx, double my, double mz
    ) {
        double bm = Math.sqrt(bx * bx + by * by + bz * bz + HardwareLimits.EPSILON);
        double nx = bx / bm, ny = by / bm, nz = bz / bm;
        double th = gamma * bm * dt;
        double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
        double nd = nx * mx + ny * my + nz * mz;
        double cx = ny * mz - nz * my;
        double cy = nz * mx - nx * mz;
        double cz = nx * my - ny * mx;
        return new MagnetisationState(
            (mx * c + cx * s + nx * nd * omc) * e2,
            (my * c + cy * s + ny * nd * omc) * e2,
            1.0 + (mz * c + cz * s + nz * nd * omc - 1.0) * e1
        );
    }

    /**
     * Pure precession around the z-axis plus T1/T2 decay. Use when
     * {@code |B_perp|²} is below {@link #B_PERP_SQ_FLOOR} (no transverse field
     * to worry about — the Rodrigues axis-of-rotation degenerates).
     */
    public static MagnetisationState zOnly(
        double bz, double gamma, double dt,
        double e1, double e2,
        double mx, double my, double mz
    ) {
        double th = gamma * bz * dt;
        double c = Math.cos(th), s = Math.sin(th);
        return new MagnetisationState(
            (mx * c - my * s) * e2,
            (mx * s + my * c) * e2,
            1.0 + (mz - 1.0) * e1
        );
    }
}
