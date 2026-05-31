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
 *
 * <p>The {@link #rodrigues}/{@link #zOnly} pair returns a
 * {@link MagnetisationState} record — convenient when the caller already has
 * scalar mx/my/mz (e.g. {@code BlochSimulator}). The {@link #rodriguesInto}/
 * {@link #zOnlyInto} pair writes back into pre-allocated arrays — zero
 * per-step allocation, used by the per-point hot loops in
 * {@link ax.xz.mri.service.simulation.SignalTraceComputer} and
 * {@link ax.xz.mri.optimisation.BlochObjectiveEngine} where allocating a
 * record per step per point produces tens of millions of short-lived objects.
 */
public final class BlochStep {
    private BlochStep() {}

    /** Threshold below which {@code |B_perp|²} is treated as zero — triggers the z-only short-circuit. */
    public static final double B_PERP_SQ_FLOOR = 1e-30;

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

    /**
     * Allocation-free Rodrigues update: writes the next magnetisation back
     * into {@code mx[p]}, {@code my[p]}, {@code mz[p]}. Numerics match
     * {@link #rodrigues} bit-for-bit.
     */
    public static void rodriguesInto(
        double bx, double by, double bz,
        double gamma, double dt,
        double e1, double e2,
        double[] mx, double[] my, double[] mz, int p
    ) {
        double mxp = mx[p], myp = my[p], mzp = mz[p];
        double bm = Math.sqrt(bx * bx + by * by + bz * bz + HardwareLimits.EPSILON);
        double nx = bx / bm, ny = by / bm, nz = bz / bm;
        double th = gamma * bm * dt;
        double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
        double nd = nx * mxp + ny * myp + nz * mzp;
        double cx = ny * mzp - nz * myp;
        double cy = nz * mxp - nx * mzp;
        double cz = nx * myp - ny * mxp;
        mx[p] = (mxp * c + cx * s + nx * nd * omc) * e2;
        my[p] = (myp * c + cy * s + ny * nd * omc) * e2;
        mz[p] = 1.0 + (mzp * c + cz * s + nz * nd * omc - 1.0) * e1;
    }

    /**
     * Allocation-free z-only update: writes the next magnetisation back
     * into {@code mx[p]}, {@code my[p]}, {@code mz[p]}. Numerics match
     * {@link #zOnly} bit-for-bit.
     */
    public static void zOnlyInto(
        double bz, double gamma, double dt,
        double e1, double e2,
        double[] mx, double[] my, double[] mz, int p
    ) {
        double mxp = mx[p], myp = my[p], mzp = mz[p];
        double th = gamma * bz * dt;
        double c = Math.cos(th), s = Math.sin(th);
        mx[p] = (mxp * c - myp * s) * e2;
        my[p] = (mxp * s + myp * c) * e2;
        mz[p] = 1.0 + (mzp - 1.0) * e1;
    }
}
