package ax.xz.mri.model.substance;

import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.output.MagneticMoment;
import ax.xz.mri.model.substance.output.SpinOutput;

import java.util.Set;

/**
 * Continuous proton magnetisation filling a bounded region of space.
 *
 * <p>Spatial layout. The substance is a Cartesian half-open box centred on the
 * origin spanning {@code [-halfExtent, +halfExtent]} on each axis, sampled at
 * {@code nX × nY × nZ} voxels. One Bloch spin per voxel; the per-grid-point
 * state is the 3-vector {@code (mx, my, mz)} evolved by the rotating-frame
 * Bloch equation.
 *
 * <p>Why the substance owns the grid. Spatial extent and sampling resolution
 * are properties of the magnetisation itself — different sims of the same
 * substance use the same grid, and visualisations derive viewport bounds from
 * the substance directly. Putting the grid on the simulation config split a
 * single concern across two records and forced every renderer + slice pane
 * to pass a separate FOV alongside the substance to do anything useful.
 *
 * <p>T1, T2, and γ live here for the same reason — they are properties of the
 * substance, not of the simulation environment.
 *
 * <p>This substance emits {@link MagneticMoment}; no optical output. It
 * declares {@link FieldSymmetry#AXISYMMETRIC_Z} as preferred — fields that
 * are themselves axisymmetric and probes that don't break the symmetry get
 * the cylindrical fast path; anything else falls back to 3D Cartesian
 * automatically.
 */
public record ContinuousMagnetisation(
    double t1Seconds,
    double t2Seconds,
    double gammaRadPerSecPerTesla,
    double mz0,
    double halfExtentXMetres,
    double halfExtentYMetres,
    double halfExtentZMetres,
    int nX,
    int nY,
    int nZ
) implements Substance {

    public ContinuousMagnetisation {
        if (!(t1Seconds > 0) || !Double.isFinite(t1Seconds))
            throw new IllegalArgumentException("ContinuousMagnetisation.t1Seconds must be finite positive");
        if (!(t2Seconds > 0) || !Double.isFinite(t2Seconds))
            throw new IllegalArgumentException("ContinuousMagnetisation.t2Seconds must be finite positive");
        if (!(gammaRadPerSecPerTesla > 0) || !Double.isFinite(gammaRadPerSecPerTesla))
            throw new IllegalArgumentException("ContinuousMagnetisation.gammaRadPerSecPerTesla must be finite positive");
        if (!Double.isFinite(mz0))
            throw new IllegalArgumentException("ContinuousMagnetisation.mz0 must be finite");
        if (!(halfExtentXMetres > 0) || !Double.isFinite(halfExtentXMetres))
            throw new IllegalArgumentException("ContinuousMagnetisation.halfExtentXMetres must be finite positive");
        if (!(halfExtentYMetres > 0) || !Double.isFinite(halfExtentYMetres))
            throw new IllegalArgumentException("ContinuousMagnetisation.halfExtentYMetres must be finite positive");
        if (!(halfExtentZMetres > 0) || !Double.isFinite(halfExtentZMetres))
            throw new IllegalArgumentException("ContinuousMagnetisation.halfExtentZMetres must be finite positive");
        if (nX < 2) throw new IllegalArgumentException("ContinuousMagnetisation.nX must be >= 2");
        if (nY < 2) throw new IllegalArgumentException("ContinuousMagnetisation.nY must be >= 2");
        if (nZ < 2) throw new IllegalArgumentException("ContinuousMagnetisation.nZ must be >= 2");
    }

    /**
     * Water at 1.0 T, γ = 2.6752 × 10⁸ rad·s⁻¹·T⁻¹, on a ±30 mm × ±30 mm × ±10 mm
     * axisymmetric-friendly box at 5 × 5 × 50 voxels. Matches the legacy
     * {@code PhysicsParams.DEFAULTS} but with spatial layout co-located.
     */
    public static ContinuousMagnetisation defaults() {
        return new ContinuousMagnetisation(
            1.0, 0.1, 267.522e6, 1.0,
            0.030, 0.030, 0.010,
            5, 5, 50);
    }

    @Override public SpinKind spinKind() { return SpinKind.BLOCH; }

    @Override public FieldSymmetry preferredSymmetry() { return FieldSymmetry.AXISYMMETRIC_Z; }

    @Override public Set<Class<? extends SpinOutput>> outputChannels() {
        return Set.of(MagneticMoment.class);
    }

    /** Half-extent corner — the (+X,+Y,+Z) octant tip of the bounding box. */
    public Vec3 halfExtent() {
        return new Vec3(halfExtentXMetres, halfExtentYMetres, halfExtentZMetres);
    }

    public ContinuousMagnetisation withT1Seconds(double v) {
        return new ContinuousMagnetisation(v, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withT2Seconds(double v) {
        return new ContinuousMagnetisation(t1Seconds, v, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withGamma(double v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, v, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withMz0(double v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, v,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withHalfExtentXMetres(double v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            v, halfExtentYMetres, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withHalfExtentYMetres(double v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, v, halfExtentZMetres, nX, nY, nZ);
    }

    public ContinuousMagnetisation withHalfExtentZMetres(double v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, v, nX, nY, nZ);
    }

    public ContinuousMagnetisation withNX(int v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, v, nY, nZ);
    }

    public ContinuousMagnetisation withNY(int v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, v, nZ);
    }

    public ContinuousMagnetisation withNZ(int v) {
        return new ContinuousMagnetisation(t1Seconds, t2Seconds, gammaRadPerSecPerTesla, mz0,
            halfExtentXMetres, halfExtentYMetres, halfExtentZMetres, nX, nY, v);
    }
}
