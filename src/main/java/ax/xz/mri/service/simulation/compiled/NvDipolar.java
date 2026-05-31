package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Magnetic dipole–dipole coupling between two NV electron spins.
 *
 * <p>The secular (energy-conserving) part of the dipolar Hamiltonian for two
 * like spins is
 * <pre>
 *   H_dd = J(r)·(1 − 3cos²θ) · [ Sz_i Sz_j − ½(Sx_i Sx_j + Sy_i Sy_j) ]
 * </pre>
 * with coupling strength {@code J(r) = μ0 γ_e² ℏ / (4π r³)} (rad/s) and θ the
 * angle between the inter-spin vector r̂ and the common NV symmetry axis n̂.
 * {@link #couplingRadPerSec} returns the prefactor {@code J(r)·(1 − 3cos²θ)}
 * in rad/s; {@link NvClusterEngine} multiplies it into the σ-operator form.
 *
 * <p>Sanity check: at r = 10 nm along the axis the coupling is ≈ 2·52 kHz —
 * the right scale for the few-tens-of-nm cluster thresholds.
 */
final class NvDipolar {
    private NvDipolar() {}

    /** Vacuum permeability, T·m/A. */
    static final double MU0 = 4.0 * Math.PI * 1e-7;
    /** Reduced Planck constant, J·s. */
    static final double HBAR = 1.054571817e-34;

    /**
     * Secular dipolar prefactor {@code J(r)·(1 − 3cos²θ)} in rad/s for the pair
     * {@code (a, b)}, with the common quantisation axis {@code axisUnit} (NV
     * symmetry axis, unit length). Returns 0 for coincident centres.
     *
     * @param gamma electron gyromagnetic ratio, rad/(s·T)
     */
    static double couplingRadPerSec(Vec3 a, Vec3 b, Vec3 axisUnit, double gamma) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double r2 = dx * dx + dy * dy + dz * dz;
        if (!(r2 > 0)) return 0.0;
        double r = Math.sqrt(r2);
        double cos = (dx * axisUnit.x() + dy * axisUnit.y() + dz * axisUnit.z()) / r;
        double geom = 1.0 - 3.0 * cos * cos;
        double j = MU0 * gamma * gamma * HBAR / (4.0 * Math.PI * r2 * r); // 4π r³
        return j * geom;
    }
}
