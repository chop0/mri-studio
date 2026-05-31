package ax.xz.mri.model.substance;

import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.output.MagneticMoment;
import ax.xz.mri.model.substance.output.PhotonClickRate;
import ax.xz.mri.model.substance.output.SpinOutput;

import java.util.List;
import java.util.Set;

/**
 * A bulk-diamond NV-ensemble substance.
 *
 * <p>The geometry is described by an {@link NvArrayGeometry} generator —
 * shape + count + extent + axis + seed — which the substance expands
 * deterministically via {@link #centres()}. The shared {@link NvPhysics}
 * record holds the ensemble-wide parameters: NV gyromagnetic ratio, bias
 * B0, homogeneous T2, polarisation efficiency, and the per-shot photon
 * model.
 *
 * <p>This substance emits both {@link MagneticMoment} (a vanishingly small
 * contribution at MR frequencies — the abstraction is uniform but the value
 * is near zero in practice) and {@link PhotonClickRate}. The click-rate
 * channels expose one output port per spectral band (currently a single
 * "red PSB" channel — the green/ZPL channel is the v1.1 extension).
 *
 * <p>The {@code laser_on} control input wires to a sequence track and
 * resets the NV state to the polarised distribution on the rising edge —
 * shot randomness (the per-block {@code θ_shot} for incoherent-readout
 * scenarios) is the substance's private property, seeded from
 * {@link #shotSeed}.
 *
 * <p>{@link #interactionThresholdMetres} controls NV–NV interaction
 * tiering: pairs closer than this distance are grouped into a joint
 * cluster by the compile-time union-find and simulated with a sparse
 * Hamiltonian over within-cluster dipolar couplings. The default of
 * 0 disables interactions (independent-NV fast path = cluster size 1).
 */
public record NvEnsemble(
    NvArrayGeometry arrayGeometry,
    NvPhysics physics,
    long shotSeed,
    double interactionThresholdMetres
) implements Substance {

    public NvEnsemble {
        if (arrayGeometry == null) throw new IllegalArgumentException("NvEnsemble.arrayGeometry must be non-null");
        if (physics == null) throw new IllegalArgumentException("NvEnsemble.physics must be non-null");
        if (!(interactionThresholdMetres >= 0))
            throw new IllegalArgumentException("NvEnsemble.interactionThresholdMetres must be non-negative");
    }

    /** Independent-NV (interaction-free) ensemble. */
    public NvEnsemble(NvArrayGeometry arrayGeometry, NvPhysics physics, long shotSeed) {
        this(arrayGeometry, physics, shotSeed, 0.0);
    }

    @Override public SpinKind spinKind() { return SpinKind.NV; }

    @Override public FieldSymmetry preferredSymmetry() {
        // NV arrays are typically planar surface arrays at fixed depth — no
        // axial symmetry on the array layout itself, so we don't ask for it.
        return FieldSymmetry.CARTESIAN_3D;
    }

    @Override public Set<Class<? extends SpinOutput>> outputChannels() {
        return Set.of(MagneticMoment.class, PhotonClickRate.class);
    }

    @Override public Set<String> controlInputs() {
        return Set.of("laser_on");
    }

    /** Materialise the per-centre list described by {@link #arrayGeometry}. */
    public List<NvCentre> centres() {
        return arrayGeometry.generate();
    }

    /**
     * Half-extent of the axis-aligned bounding box that contains every NV
     * centre, with a small margin so visualisations don't render points
     * sitting on the box edge. Returns a 1 nm cubed default for an empty
     * ensemble so renderers still get a non-degenerate box to size against.
     */
    @Override
    public Vec3 halfExtent() {
        var cs = centres();
        if (cs.isEmpty()) return new Vec3(1e-9, 1e-9, 1e-9);
        double xMax = 0, yMax = 0, zMax = 0;
        for (var c : cs) {
            xMax = Math.max(xMax, Math.abs(c.xMetres()));
            yMax = Math.max(yMax, Math.abs(c.yMetres()));
            zMax = Math.max(zMax, Math.abs(c.zMetres()));
        }
        double margin = 0.1;
        return new Vec3(
            Math.max(1e-9, xMax * (1 + margin)),
            Math.max(1e-9, yMax * (1 + margin)),
            Math.max(1e-9, zMax * (1 + margin)));
    }

    /**
     * Number of NV centres this ensemble describes. For {@link NvArrayShape#GRID_XY}
     * the array is {@code n × n}, so the count is the square of {@code n}; for
     * every other shape it's {@code n} directly.
     */
    public int centreCount() {
        var shape = arrayGeometry.shape();
        int n = arrayGeometry.n();
        return switch (shape) {
            case GRID_XY -> n * n;
            case CUSTOM  -> arrayGeometry.customCentres().size();
            default      -> n;
        };
    }

    public NvEnsemble withInteractionThreshold(double v) {
        return new NvEnsemble(arrayGeometry, physics, shotSeed, v);
    }

    public NvEnsemble withShotSeed(long v) {
        return new NvEnsemble(arrayGeometry, physics, v, interactionThresholdMetres);
    }

    public NvEnsemble withArrayGeometry(NvArrayGeometry g) {
        return new NvEnsemble(g, physics, shotSeed, interactionThresholdMetres);
    }

    public NvEnsemble withPhysics(NvPhysics p) {
        return new NvEnsemble(arrayGeometry, p, shotSeed, interactionThresholdMetres);
    }
}
