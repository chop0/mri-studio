package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;

import java.util.List;

/**
 * {@link CompiledSubstance} for {@link NvEnsemble}: one NV per centre.
 *
 * <p>State layout in the fused state vector slice: 3-real Bloch-vector
 * reduction of the NV {@code m_s = 0 ↔ m_s = ±1} effective qubit —
 * {@code [sx0, sy0, sz0, sx1, sy1, sz1, …]}. {@code sz = +1} ↔ fully polarised
 * to {@code m_s = 0}; {@code sz = -1} ↔ fully populated in the bright/dark
 * mixed state used post-pump-saturation.
 *
 * <p>Per-dt evolution: Rodrigues rotation under {@code localBField} with
 * {@code γ_NV ≈ 2π·28.024 GHz/T} plus T₁/T₂ relaxation. The rotating frame is
 * at the MW carrier; the {@code Bz} input is the detuning, {@code Bx/By} the
 * Rabi drive from the MW coil.
 *
 * <p>Laser control input {@code laser_on} (0 or 1) gates two regimes:
 * <ul>
 *   <li>{@code laser_on = 0} (dark): coherent evolution + relaxation.</li>
 *   <li>{@code laser_on = 1} (pump/read): T₂ clamps to the pump-saturation
 *       time so coherence decays in ~10 ns, and {@code sz} relaxes to
 *       {@code +1} on the optical-pumping timescale (~300 ns). The first
 *       rising edge polarises hard.</li>
 * </ul>
 *
 * <p>Photon-click rate from {@link #emitPhotonClickRates} reflects the
 * current spin polarisation: {@code rate = (cBright + cDark)/2 ·
 * pumpedFlag · (1 + contrast · sz)} during the laser window;
 * the optical counter integrates dt-windows of this rate.
 */
public final class NvKernel implements CompiledSubstance {

    /** γ_e for the NV ground-state triplet, rad/s/T. */
    private static final double GAMMA_NV = 2 * Math.PI * 28.024e9;
    /** Coherence T₂ while the laser is on (pump saturation kills coherence fast). */
    private static final double T2_LASER_ON = 10e-9;
    /** Population-pump time constant while the laser is on. */
    private static final double T1_LASER_PUMP = 300e-9;

    private final NvEnsemble source;
    private final List<NvCentre> centres;
    private final List<String> opticalChannelNames;
    private final int[][] clusters;
    /**
     * Laser_on value sampled at the most recent {@link #advance} call.
     * Read by {@link #emitPhotonClickRates} so the click rate window
     * matches the kernel's evolution.
     */
    private double lastLaserOn;

    NvKernel(NvEnsemble source, int[][] clusters) {
        this.source = source;
        this.centres = source.centres();
        this.opticalChannelNames = List.of("red");
        this.clusters = clusters;
    }

    @Override public Substance source() { return source; }
    @Override public int spinCount() { return centres.size(); }

    @Override
    public Vec3 spinPosition(int i) {
        var c = centres.get(i);
        return new Vec3(c.xMetres(), c.yMetres(), c.zMetres());
    }

    @Override public int stateSize() { return 3 * spinCount(); }

    @Override
    public void reset(double[] state, int offset) {
        // Polarised m_s=0: Bloch-vector reduction starts at +z.
        for (int i = 0; i < spinCount(); i++) {
            int base = offset + 3 * i;
            state[base    ] = 0.0;
            state[base + 1] = 0.0;
            state[base + 2] = 1.0;
        }
        lastLaserOn = 0;
    }

    @Override public int controlInputCount() { return 1; }
    @Override public List<String> controlInputNames() { return List.of("laser_on"); }

    @Override
    public void advance(double[] state, int offset,
                        double[] localBField, double[] controlInputs,
                        double dt, double tSeconds) {
        double laserOn = controlInputs.length > 0 ? controlInputs[0] : 0;
        // Pump regime — clamp T₂ short, drive sz → +1 on optical-pump timescale.
        boolean laser = laserOn > 0.5;

        var phys = source.physics();
        // NV ground-state T₁ ≈ 1 ms; NvPhysics records the homogeneous T₂ only
        // (treat as +∞ for the v1 atomic model). Pumping while the laser is on
        // collapses both timescales onto the optical-pump constants below.
        double t1Dark = 1.0;
        double t2Dark = Double.isFinite(phys.t2homogSec()) ? phys.t2homogSec() : 1.0;
        double t1 = laser ? T1_LASER_PUMP : t1Dark;
        double t2 = laser ? T2_LASER_ON   : t2Dark;
        double e1 = Math.exp(-dt / t1);
        double e2 = Math.exp(-dt / t2);

        int n = spinCount();
        for (int i = 0; i < n; i++) {
            int sb = offset + 3 * i;
            int fb = 3 * i;
            double bx = localBField[fb];
            double by = localBField[fb + 1];
            double bz = localBField[fb + 2];

            double sx = state[sb], sy = state[sb + 1], sz = state[sb + 2];
            double bmag2 = bx * bx + by * by + bz * bz;
            if (bmag2 < 1e-30) {
                // Pure relaxation toward +z.
                state[sb    ] = sx * e2;
                state[sb + 1] = sy * e2;
                state[sb + 2] = 1.0 + (sz - 1.0) * e1;
            } else {
                double bm = Math.sqrt(bmag2);
                double nx = bx / bm, ny = by / bm, nz = bz / bm;
                double th = GAMMA_NV * bm * dt;
                double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
                double nd = nx * sx + ny * sy + nz * sz;
                double cx = ny * sz - nz * sy;
                double cy = nz * sx - nx * sz;
                double cz = nx * sy - ny * sx;
                state[sb    ] = (sx * c + cx * s + nx * nd * omc) * e2;
                state[sb + 1] = (sy * c + cy * s + ny * nd * omc) * e2;
                state[sb + 2] = 1.0 + (sz * c + cz * s + nz * nd * omc - 1.0) * e1;
            }
        }
        lastLaserOn = laserOn;
    }

    @Override
    public void emitMagneticMoments(double[] state, int offset, double[] momentsOut) {
        // NV magnetic-moment contribution to MR-receive reciprocity is
        // negligible — emit zeros for abstraction uniformity.
        int n = spinCount();
        for (int i = 0; i < n; i++) {
            int fb = 3 * i;
            momentsOut[fb    ] = 0.0;
            momentsOut[fb + 1] = 0.0;
            momentsOut[fb + 2] = 0.0;
        }
    }

    @Override public int opticalChannelCount() { return 1; }
    @Override public List<String> opticalChannelNames() { return opticalChannelNames; }

    /**
     * Convert {@link ax.xz.mri.model.nv.NvPhysics#cBright} (photons-per-shot at
     * a ~1 µs integration window) into a per-second rate. NV physics defaults
     * give cBright ≈ 0.030 ⇒ 30 kHz bright rate per NV — typical for a
     * confocal-pumped centre at ~50 kcps shot noise.
     */
    private static final double PHOTONS_PER_SHOT_TO_HZ = 1.0e6;

    @Override
    public void emitPhotonClickRates(double[] state, int offset, double[] ratesOut) {
        // Per-NV red-PSB rate (Hz).
        //   P₀ = (1 + sz) / 2.
        //   rate = (P₀·cBright + (1−P₀)·cDark) · PHOTONS_PER_SHOT_TO_HZ
        // Laser-off: fluorescence requires the pump beam — zero counts.
        if (lastLaserOn <= 0.5) {
            int n = spinCount();
            for (int i = 0; i < n; i++) ratesOut[i] = 0;
            return;
        }
        var phys = source.physics();
        double cB = phys.cBright() * PHOTONS_PER_SHOT_TO_HZ;
        double cD = phys.cDark()   * PHOTONS_PER_SHOT_TO_HZ;
        int n = spinCount();
        for (int i = 0; i < n; i++) {
            double sz = state[offset + 3 * i + 2];
            double p0 = 0.5 * (1.0 + sz);
            ratesOut[i] = p0 * cB + (1.0 - p0) * cD;
        }
    }

    /** Cluster groups produced by compile-time union-find — exposed for procedures + tests. */
    public int[][] clusters() {
        int[][] copy = new int[clusters.length][];
        for (int i = 0; i < clusters.length; i++) copy[i] = clusters[i].clone();
        return copy;
    }
}
