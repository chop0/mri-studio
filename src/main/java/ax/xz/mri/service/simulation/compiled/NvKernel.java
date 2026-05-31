package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;

import java.util.List;

/**
 * {@link CompiledSubstance} for {@link NvEnsemble}: one effective qubit per NV
 * centre, evolved in compile-time clusters.
 *
 * <p>The {@link ax.xz.mri.model.simulation.NvSimulationMethod} on the
 * simulation config decides the clustering (which NVs couple, and the joint
 * cap); the compiler hands the resulting partition in. Each cluster occupies a
 * contiguous block of the fused state vector:
 *
 * <ul>
 *   <li><b>Singleton</b> (the common case, and every NV when interactions are
 *       off): a 3-real Bloch vector {@code (sx,sy,sz)}. Per-dt evolution is the
 *       closed-form Rodrigues rotation under {@code localBField} with
 *       {@code γ_NV} plus T₁/T₂ relaxation — the classical model, unchanged.</li>
 *   <li><b>Coupled cluster</b> ({@code k > 1}): a {@code 2^k} density matrix
 *       carrying the secular dipolar coupling between members, evolved by
 *       {@link NvClusterEngine}. Reduces to the singleton dynamics exactly when
 *       the coupling is zero.</li>
 * </ul>
 *
 * <p>{@code sz = +1} ↔ fully polarised to {@code m_s=0} (bright). Laser control
 * input {@code laser_on} (0 or 1) gates the dark regime (coherent evolution +
 * relaxation) and the pump/read regime (T₂ ~10 ns, sz → +1 on ~300 ns). The
 * photon-click rate per NV reflects its spin polarisation during the laser
 * window; coupled-cluster members read their polarisation from the reduced
 * single-qubit density matrix (partial trace).
 */
public final class NvKernel implements CompiledSubstance {

    /** Coherence T₂ while the laser is on (pump saturation kills coherence fast). */
    private static final double T2_LASER_ON = 10e-9;
    /** Population-pump time constant while the laser is on. */
    private static final double T1_LASER_PUMP = 300e-9;
    /** Photons-per-shot → per-second rate at a ~1 µs integration window. */
    private static final double PHOTONS_PER_SHOT_TO_HZ = 1.0e6;

    private final NvEnsemble source;
    private final List<NvCentre> centres;
    private final List<String> opticalChannelNames;
    private final int[][] clusters;
    private final double gamma;

    // Per-cluster layout into the fused state-vector slice.
    private final int[] blockOffset;        // start of each cluster block, relative to the substance offset
    private final boolean[] singleton;      // size-1 cluster → 3-real Bloch block
    private final NvClusterEngine[] engine; // null for singletons
    private final int totalSlots;
    // Routing: global NV index → owning cluster + local index within it.
    private final int[] nvCluster;
    private final int[] nvLocal;
    // Scratch for gathering a cluster's per-member local fields.
    private final double[] clusterB;

    private double lastLaserOn;

    NvKernel(NvEnsemble source, int[][] clusters) {
        this.source = source;
        this.centres = source.centres();
        this.opticalChannelNames = List.of("red");
        this.gamma = source.physics().gammaRadPerSecPerTesla();

        int c = clusters.length;
        this.clusters = new int[c][];
        this.blockOffset = new int[c];
        this.singleton = new boolean[c];
        this.engine = new NvClusterEngine[c];
        int nNv = centres.size();
        this.nvCluster = new int[nNv];
        this.nvLocal = new int[nNv];

        int off = 0, maxMembers = 1;
        for (int ci = 0; ci < c; ci++) {
            int[] members = clusters[ci].clone();
            this.clusters[ci] = members;
            blockOffset[ci] = off;
            maxMembers = Math.max(maxMembers, members.length);
            for (int local = 0; local < members.length; local++) {
                nvCluster[members[local]] = ci;
                nvLocal[members[local]] = local;
            }
            if (members.length == 1) {
                singleton[ci] = true;
                off += 3;
            } else {
                engine[ci] = buildEngine(members);
                off += engine[ci].stateSlots();
            }
        }
        this.totalSlots = off;
        this.clusterB = new double[3 * maxMembers];
    }

    private NvClusterEngine buildEngine(int[] members) {
        int k = members.length;
        int nPairs = k * (k - 1) / 2;
        int[] pq = new int[nPairs];
        int[] pp = new int[nPairs];
        double[] pc = new double[nPairs];
        // Common quantisation axis: v1 ensembles are single-orientation, so use
        // the first member's NV axis for the secular-dipolar geometry.
        Vec3 axis = centres.get(members[0]).axis().asVec3();
        int idx = 0;
        for (int q = 0; q < k; q++) {
            Vec3 a = posOf(members[q]);
            for (int p = q + 1; p < k; p++) {
                Vec3 b = posOf(members[p]);
                pq[idx] = q;
                pp[idx] = p;
                pc[idx] = NvDipolar.couplingRadPerSec(a, b, axis, gamma);
                idx++;
            }
        }
        return new NvClusterEngine(k, pq, pp, pc, gamma);
    }

    private Vec3 posOf(int globalIdx) {
        var ctr = centres.get(globalIdx);
        return new Vec3(ctr.xMetres(), ctr.yMetres(), ctr.zMetres());
    }

    @Override public Substance source() { return source; }
    @Override public int spinCount() { return centres.size(); }

    @Override
    public Vec3 spinPosition(int i) {
        return posOf(i);
    }

    @Override public int stateSize() { return totalSlots; }

    @Override
    public void reset(double[] state, int offset) {
        for (int ci = 0; ci < clusters.length; ci++) {
            int base = offset + blockOffset[ci];
            if (singleton[ci]) {
                state[base] = 0.0;
                state[base + 1] = 0.0;
                state[base + 2] = 1.0;          // polarised ms=0
            } else {
                engine[ci].reset(state, base);
            }
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
        boolean laser = laserOn > 0.5;

        var phys = source.physics();
        double t1Dark = 1.0;
        double t2Dark = Double.isFinite(phys.t2homogSec()) ? phys.t2homogSec() : 1.0;
        double t1 = laser ? T1_LASER_PUMP : t1Dark;
        double t2 = laser ? T2_LASER_ON   : t2Dark;
        double e1 = Math.exp(-dt / t1);
        double e2 = Math.exp(-dt / t2);

        for (int ci = 0; ci < clusters.length; ci++) {
            int[] members = clusters[ci];
            int base = offset + blockOffset[ci];
            if (singleton[ci]) {
                advanceSingleton(state, base, localBField, 3 * members[0], dt, e1, e2);
            } else {
                for (int local = 0; local < members.length; local++) {
                    int fb = 3 * members[local];
                    clusterB[3 * local]     = localBField[fb];
                    clusterB[3 * local + 1] = localBField[fb + 1];
                    clusterB[3 * local + 2] = localBField[fb + 2];
                }
                engine[ci].advance(state, base, clusterB, dt, e1, e2);
            }
        }
        lastLaserOn = laserOn;
    }

    /** Closed-form Bloch update for a single NV — the classical model, unchanged. */
    private void advanceSingleton(double[] state, int sb, double[] localBField, int fb,
                                  double dt, double e1, double e2) {
        double bx = localBField[fb], by = localBField[fb + 1], bz = localBField[fb + 2];
        double sx = state[sb], sy = state[sb + 1], sz = state[sb + 2];
        double bmag2 = bx * bx + by * by + bz * bz;
        if (bmag2 < 1e-30) {
            state[sb]     = sx * e2;
            state[sb + 1] = sy * e2;
            state[sb + 2] = 1.0 + (sz - 1.0) * e1;
        } else {
            double bm = Math.sqrt(bmag2);
            double nx = bx / bm, ny = by / bm, nz = bz / bm;
            double th = gamma * bm * dt;
            double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
            double nd = nx * sx + ny * sy + nz * sz;
            double cx = ny * sz - nz * sy;
            double cy = nz * sx - nx * sz;
            double cz = nx * sy - ny * sx;
            state[sb]     = (sx * c + cx * s + nx * nd * omc) * e2;
            state[sb + 1] = (sy * c + cy * s + ny * nd * omc) * e2;
            state[sb + 2] = 1.0 + (sz * c + cz * s + nz * nd * omc - 1.0) * e1;
        }
    }

    @Override
    public void emitMagneticMoments(double[] state, int offset, double[] momentsOut) {
        // NV magnetic-moment contribution to MR-receive reciprocity is negligible.
        int n = 3 * spinCount();
        for (int i = 0; i < n; i++) momentsOut[i] = 0.0;
    }

    @Override public int opticalChannelCount() { return 1; }
    @Override public List<String> opticalChannelNames() { return opticalChannelNames; }

    @Override
    public void emitPhotonClickRates(double[] state, int offset, double[] ratesOut) {
        int n = spinCount();
        if (lastLaserOn <= 0.5) {
            for (int i = 0; i < n; i++) ratesOut[i] = 0;
            return;
        }
        var phys = source.physics();
        double cB = phys.cBright() * PHOTONS_PER_SHOT_TO_HZ;
        double cD = phys.cDark()   * PHOTONS_PER_SHOT_TO_HZ;
        for (int g = 0; g < n; g++) {
            int ci = nvCluster[g];
            int base = offset + blockOffset[ci];
            double sz = singleton[ci]
                ? state[base + 2]
                : engine[ci].sz(state, base, nvLocal[g]);
            double p0 = 0.5 * (1.0 + sz);
            ratesOut[g] = p0 * cB + (1.0 - p0) * cD;
        }
    }

    /** Cluster groups produced by compile-time union-find — exposed for procedures + tests. */
    public int[][] clusters() {
        int[][] copy = new int[clusters.length][];
        for (int i = 0; i < clusters.length; i++) copy[i] = clusters[i].clone();
        return copy;
    }
}
