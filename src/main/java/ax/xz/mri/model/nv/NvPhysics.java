package ax.xz.mri.model.nv;

/**
 * Lumped NV-physics parameters shared by the whole ensemble.
 *
 * <p>v1 uses a linearised forward model where the only physics that matters
 * is the per-NV gyromagnetic phase {@code γτ B_z} accumulated during free
 * precession plus the PL contrast model. This record holds the smallest set
 * of physics knobs required for that model:
 *
 * <ul>
 *   <li>{@code gammaRadPerSecPerTesla} — NV electron gyro = {@code 2π·28.024 GHz/T}.</li>
 *   <li>{@code biasB0_T} — static bias along the NV axis (sets the rotating-frame
 *       resonance condition; folded into the action-imposed phase).</li>
 *   <li>{@code t2homogSec} — homogeneous T₂; converted to a per-step
 *       exp(-dt/T₂) coherence decay if {@code modelDephasing} is true.</li>
 *   <li>{@code polarisationEta} — fraction of population pumped into m_s=0 by
 *       the laser. v1 treats laser pulses as instantaneous projectors.</li>
 *   <li>{@code cBright}, {@code cDark} — PL photons per shot in the m_s=0
 *       (bright) and m_s=±1 (dark) states. The summed-PL observable
 *       {@code M = (baseline − PL)/contrast} divides through these.</li>
 * </ul>
 */
public record NvPhysics(
    double gammaRadPerSecPerTesla,
    double biasB0_T,
    double t2homogSec,
    double polarisationEta,
    double cBright,
    double cDark,
    boolean modelDephasing
) {

    /** NV electron γ/(2π) = 28.024 GHz/T → γ = 2π·28.024e9 rad/(s·T). */
    public static final double GAMMA_NV_RAD_PER_SEC_PER_TESLA = 2.0 * Math.PI * 28.024e9;

    public NvPhysics {
        if (!(gammaRadPerSecPerTesla > 0) || !Double.isFinite(gammaRadPerSecPerTesla)) {
            throw new IllegalArgumentException("NvPhysics.gammaRadPerSecPerTesla must be positive finite");
        }
        if (!Double.isFinite(biasB0_T))                  throw new IllegalArgumentException("NvPhysics.biasB0_T must be finite");
        if (Double.isNaN(t2homogSec) || !(t2homogSec > 0)) {
            throw new IllegalArgumentException("NvPhysics.t2homogSec must be positive (use Double.POSITIVE_INFINITY for 'no decay')");
        }
        if (!(polarisationEta >= 0 && polarisationEta <= 1)) {
            throw new IllegalArgumentException("NvPhysics.polarisationEta must be in [0, 1], got " + polarisationEta);
        }
        if (!(cBright > 0))                  throw new IllegalArgumentException("NvPhysics.cBright must be positive");
        if (!(cDark > 0))                    throw new IllegalArgumentException("NvPhysics.cDark must be positive");
        if (!(cBright > cDark))              throw new IllegalArgumentException("NvPhysics: cBright must exceed cDark (positive contrast)");
    }

    /** Defaults that match the Python adaptive-gradient scripts (γ = 2π·28.024 GHz/T, η = 0.92, contrast = 0.030/0.027). */
    public static NvPhysics defaults() {
        return new NvPhysics(
            GAMMA_NV_RAD_PER_SEC_PER_TESLA,
            0.0,
            Double.POSITIVE_INFINITY,
            0.92,
            0.030, 0.027,
            false
        );
    }

    /** Differential PL contrast between bright (m_s=0) and dark (m_s=±1). */
    public double contrast() { return cBright - cDark; }
}
