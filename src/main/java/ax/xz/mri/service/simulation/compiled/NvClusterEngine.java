package ax.xz.mri.service.simulation.compiled;

/**
 * Joint quantum evolution of one cluster of {@code k} coupled NV centres,
 * modelled as {@code k} effective qubits (the {@code ms=0 ↔ ms=-1} working
 * transition). The cluster state is a {@code 2^k × 2^k} density matrix ρ
 * evolved per timestep by Lie–Trotter operator splitting:
 *
 * <ol>
 *   <li><b>Unitary</b> {@code ρ ← U ρ U†}, {@code U = exp(-i H dt)}, with
 *       {@code H = Σ_q (γ/2)(Bx σx + By σy + Bz σz)_q} (Zeeman, per qubit, from
 *       the local field) {@code + Σ_{q<p} c·[¼ σz_qσz_p − ⅛(σx_qσx_p +
 *       σy_qσy_p)]} (secular dipolar; {@code c} = {@link NvDipolar} prefactor).</li>
 *   <li><b>Dissipators</b> per qubit: pure dephasing then amplitude damping
 *       toward {@code |0⟩} (= optical pump / T₁). The two compose so a single
 *       qubit ({@code k=1}) reproduces the classical Bloch update
 *       {@code (sx,sy)·e2}, {@code sz → 1+(sz-1)·e1} exactly.</li>
 * </ol>
 *
 * <p>{@code exp(-iH dt)} uses scaling-and-squaring with a Taylor series — exact
 * to round-off for both small (resolved-pulse) and large (single-step free-
 * precession) {@code ‖H‖dt}, library-free, and robust to the huge dynamic
 * range of {@code γ·B·dt}. Density matrices carry the mixed states that
 * relaxation and pumping produce; ρ is Hermitian-packed into {@code 4^k} reals
 * in the fused state vector ({@code D} real diagonal + {@code D(D-1)} for the
 * complex upper triangle, {@code D = 2^k}).
 *
 * <p>Cheap by construction: only NVs within the coupling cutoff form a cluster
 * with {@code k > 1}; isolated NVs never reach this engine (the kernel runs the
 * 3-real Bloch fast path). All matrices are dense {@code D×D} with {@code D ≤
 * 2^{maxClusterSize}}.
 */
final class NvClusterEngine {

    private final int k;
    private final int dim;          // 2^k
    private final double gamma;

    // Within-cluster pairs (local qubit indices) and their secular dipolar
    // prefactor c = J(r)·(1-3cos²θ) in rad/s.
    private final int[] pairQ;
    private final int[] pairP;
    private final double[] pairC;

    // Complex scratch (row-major re/im), reused every step — no per-step alloc.
    private final double[] rRe, rIm;     // ρ
    private final double[] hRe, hIm;     // H
    private final double[] uRe, uIm;     // U
    private final double[] aRe, aIm;     // Taylor accumulator / matmul A
    private final double[] tRe, tIm;     // Taylor term / matmul output
    private final double[] sRe, sIm;     // square / second matmul output
    private final double[] dRe, dIm;     // dissipator snapshot

    NvClusterEngine(int k, int[] pairQ, int[] pairP, double[] pairC, double gamma) {
        if (k < 1) throw new IllegalArgumentException("cluster size must be ≥ 1");
        this.k = k;
        this.dim = 1 << k;
        this.gamma = gamma;
        this.pairQ = pairQ.clone();
        this.pairP = pairP.clone();
        this.pairC = pairC.clone();
        int n = dim * dim;
        rRe = new double[n]; rIm = new double[n];
        hRe = new double[n]; hIm = new double[n];
        uRe = new double[n]; uIm = new double[n];
        aRe = new double[n]; aIm = new double[n];
        tRe = new double[n]; tIm = new double[n];
        sRe = new double[n]; sIm = new double[n];
        dRe = new double[n]; dIm = new double[n];
    }

    /** Real slots this cluster occupies in the fused state vector (= 4^k). */
    int stateSlots() { return dim * dim; }

    /** Initialise to the fully-polarised product state |0…0⟩⟨0…0| (all NVs in ms=0). */
    void reset(double[] state, int off) {
        int n = dim * dim;
        for (int i = 0; i < n; i++) state[off + i] = 0.0;
        state[off] = 1.0;            // packed diagonal[0] = ρ00 = 1
    }

    /**
     * One timestep. {@code localB} is length {@code 3*k}: (Bx,By,Bz) per cluster
     * member in local order. {@code e1 = exp(-dt/T1)}, {@code e2 = exp(-dt/T2)}
     * are the longitudinal / transverse decay factors for this regime (the
     * caller picks dark vs pump constants), matching the Bloch kernel.
     */
    void advance(double[] state, int off, double[] localB, double dt, double e1, double e2) {
        unpack(state, off);
        buildHamiltonian(localB);
        matrixExpMinusIHdt(dt);      // uRe/uIm = exp(-iH dt)
        conjugateSandwich();         // ρ ← U ρ U†
        // Dephasing factor folded so amplitude damping's √e1 coherence loss
        // brings the total transverse decay to exactly e2 (k=1 Bloch parity).
        double f = e2 / Math.sqrt(Math.max(e1, 1e-300));
        if (f > 1.0) f = 1.0;
        for (int q = 0; q < k; q++) {
            dephaseQubit(q, f);
            amplitudeDampQubit(q, e1);
        }
        renormaliseTrace();
        pack(state, off);
    }

    /* ── Single-qubit reduced expectations (for readout / moments) ─────────── */

    /** ⟨σz⟩ of member {@code q} = Σ_b s_q(b)·ρ[b][b]. */
    double sz(double[] state, int off, int q) {
        double acc = 0;
        int mask = 1 << q;
        for (int b = 0; b < dim; b++) {
            double pop = state[off + b];                 // packed diagonal is real
            acc += ((b & mask) == 0 ? pop : -pop);
        }
        return acc;
    }

    /** ⟨σx⟩ of member {@code q} from the reduced 1-qubit ρ: 2·Re ρ_q[0,1]. */
    double sx(double[] state, int off, int q) {
        unpack(state, off);
        int mask = 1 << q;
        double re = 0;
        for (int b = 0; b < dim; b++) {
            if ((b & mask) != 0) continue;               // b has qubit q = 0
            int bp = b | mask;                            // partner with qubit q = 1
            re += rRe[b * dim + bp];                      // ρ[b][bp]
        }
        return 2.0 * re;
    }

    /** ⟨σy⟩ of member {@code q} from the reduced 1-qubit ρ: -2·Im ρ_q[0,1]. */
    double sy(double[] state, int off, int q) {
        unpack(state, off);
        int mask = 1 << q;
        double im = 0;
        for (int b = 0; b < dim; b++) {
            if ((b & mask) != 0) continue;
            int bp = b | mask;
            im += rIm[b * dim + bp];                      // ρ[b][bp]
        }
        return -2.0 * im;
    }

    /* ── Hamiltonian ───────────────────────────────────────────────────────── */

    private void buildHamiltonian(double[] localB) {
        int n = dim * dim;
        for (int i = 0; i < n; i++) { hRe[i] = 0; hIm[i] = 0; }
        double half = gamma * 0.5;

        // Zeeman, per qubit.
        for (int q = 0; q < k; q++) {
            double bx = localB[3 * q], by = localB[3 * q + 1], bz = localB[3 * q + 2];
            int mask = 1 << q;
            for (int b = 0; b < dim; b++) {
                int sgn = (b & mask) == 0 ? 1 : -1;
                hRe[b * dim + b] += half * bz * sgn;      // (γ/2) Bz σz
                int fb = b ^ mask;                        // σx / σy flip
                hRe[fb * dim + b] += half * bx;           // (γ/2) Bx σx
                hIm[fb * dim + b] += half * by * sgn;     // (γ/2) By σy
            }
        }

        // Secular dipolar, per within-cluster pair.
        for (int pi = 0; pi < pairC.length; pi++) {
            int q = pairQ[pi], p = pairP[pi];
            double c = pairC[pi];
            int mq = 1 << q, mp = 1 << p;
            double diag = c * 0.25;                       // (c/4) σz σz
            double flip = -c * 0.25;                      // flip-flop on opposite bits
            for (int b = 0; b < dim; b++) {
                int sq = (b & mq) == 0 ? 1 : -1;
                int sp = (b & mp) == 0 ? 1 : -1;
                hRe[b * dim + b] += diag * sq * sp;
                if (sq != sp) {                            // |↑↓⟩ ↔ |↓↑⟩ only
                    int fb = b ^ mq ^ mp;
                    hRe[fb * dim + b] += flip;
                }
            }
        }
    }

    /* ── exp(-i H dt) via scaling-and-squaring Taylor ──────────────────────── */

    private void matrixExpMinusIHdt(double dt) {
        int n = dim * dim;
        // M = -i H dt  →  Mre = Him*dt, Mim = -Hre*dt.  (store M in u as the base)
        for (int i = 0; i < n; i++) {
            uRe[i] = hIm[i] * dt;
            uIm[i] = -hRe[i] * dt;
        }
        // Scale so ‖M'‖₁ ≤ 0.5.
        double norm = oneNorm(uRe, uIm);
        int s = 0;
        while (norm > 0.5) { norm *= 0.5; s++; }
        double scale = 1.0 / (1 << s);
        for (int i = 0; i < n; i++) { uRe[i] *= scale; uIm[i] *= scale; }

        // Taylor: acc = I + M' + M'²/2! + … ; term_n = term_{n-1}·M'/n.
        setIdentity(aRe, aIm);     // acc
        setIdentity(tRe, tIm);     // term
        for (int order = 1; order <= 18; order++) {
            // term ← term · M' / order   (M' currently in uRe/uIm)
            mul(tRe, tIm, uRe, uIm, sRe, sIm);
            double inv = 1.0 / order;
            boolean tiny = true;
            for (int i = 0; i < n; i++) {
                double re = sRe[i] * inv, im = sIm[i] * inv;
                tRe[i] = re; tIm[i] = im;
                aRe[i] += re; aIm[i] += im;
                if (tiny && (Math.abs(re) > 1e-18 || Math.abs(im) > 1e-18)) tiny = false;
            }
            if (tiny) break;
        }
        // U = acc; square s times.
        System.arraycopy(aRe, 0, uRe, 0, n);
        System.arraycopy(aIm, 0, uIm, 0, n);
        for (int i = 0; i < s; i++) {
            mul(uRe, uIm, uRe, uIm, sRe, sIm);
            System.arraycopy(sRe, 0, uRe, 0, n);
            System.arraycopy(sIm, 0, uIm, 0, n);
        }
    }

    /** ρ ← U ρ U†. */
    private void conjugateSandwich() {
        mul(uRe, uIm, rRe, rIm, tRe, tIm);          // T = U ρ
        mulDagger(tRe, tIm, uRe, uIm, rRe, rIm);    // ρ = T U†
    }

    /* ── Dissipators ───────────────────────────────────────────────────────── */

    /** Pure dephasing on qubit q: scale every ρ[a][b] with differing bit q by f. */
    private void dephaseQubit(int q, double f) {
        int mask = 1 << q;
        for (int a = 0; a < dim; a++) {
            int abit = a & mask;
            for (int b = 0; b < dim; b++) {
                if ((b & mask) != abit) {
                    int idx = a * dim + b;
                    rRe[idx] *= f; rIm[idx] *= f;
                }
            }
        }
    }

    /** Amplitude damping on qubit q toward |0⟩ with retention e1 (= exp(-dt/T1)). */
    private void amplitudeDampQubit(int q, double e1) {
        int n = dim * dim;
        System.arraycopy(rRe, 0, dRe, 0, n);
        System.arraycopy(rIm, 0, dIm, 0, n);
        int mask = 1 << q;
        double sqrtE1 = Math.sqrt(Math.max(e1, 0.0));
        double gain = 1.0 - e1;
        for (int a = 0; a < dim; a++) {
            double da = (a & mask) == 0 ? 1.0 : sqrtE1;
            for (int b = 0; b < dim; b++) {
                double db = (b & mask) == 0 ? 1.0 : sqrtE1;
                int idx = a * dim + b;
                double re = da * db * dRe[idx];
                double im = da * db * dIm[idx];
                if ((a & mask) == 0 && (b & mask) == 0) {     // population promoted from |1⟩
                    int as = a | mask, bs = b | mask;
                    re += gain * dRe[as * dim + bs];
                    im += gain * dIm[as * dim + bs];
                }
                rRe[idx] = re; rIm[idx] = im;
            }
        }
    }

    private void renormaliseTrace() {
        double tr = 0;
        for (int b = 0; b < dim; b++) tr += rRe[b * dim + b];
        if (tr <= 0 || !Double.isFinite(tr)) return;
        double inv = 1.0 / tr;
        int n = dim * dim;
        for (int i = 0; i < n; i++) { rRe[i] *= inv; rIm[i] *= inv; }
    }

    /* ── Packing (Hermitian D×D ↔ 4^k reals) ───────────────────────────────── */

    private void unpack(double[] state, int off) {
        int n = dim * dim;
        for (int i = 0; i < n; i++) { rRe[i] = 0; rIm[i] = 0; }
        for (int i = 0; i < dim; i++) rRe[i * dim + i] = state[off + i];
        int base = off + dim;
        for (int i = 0; i < dim; i++) {
            for (int j = i + 1; j < dim; j++) {
                int p = base + 2 * pairLinear(i, j);
                double re = state[p], im = state[p + 1];
                rRe[i * dim + j] = re;  rIm[i * dim + j] = im;
                rRe[j * dim + i] = re;  rIm[j * dim + i] = -im;
            }
        }
    }

    private void pack(double[] state, int off) {
        for (int i = 0; i < dim; i++) state[off + i] = rRe[i * dim + i];
        int base = off + dim;
        for (int i = 0; i < dim; i++) {
            for (int j = i + 1; j < dim; j++) {
                int p = base + 2 * pairLinear(i, j);
                state[p]     = rRe[i * dim + j];
                state[p + 1] = rIm[i * dim + j];
            }
        }
    }

    private int pairLinear(int i, int j) {
        // index of (i<j) in row-major upper-triangle order
        return i * (2 * dim - i - 1) / 2 + (j - i - 1);
    }

    /* ── Complex matrix primitives (row-major, D×D) ────────────────────────── */

    private void setIdentity(double[] re, double[] im) {
        int n = dim * dim;
        for (int i = 0; i < n; i++) { re[i] = 0; im[i] = 0; }
        for (int i = 0; i < dim; i++) re[i * dim + i] = 1.0;
    }

    /** C = A · B. */
    private void mul(double[] aRe, double[] aIm, double[] bRe, double[] bIm,
                     double[] cRe, double[] cIm) {
        for (int i = 0; i < dim; i++) {
            for (int j = 0; j < dim; j++) {
                double re = 0, im = 0;
                int ai = i * dim;
                for (int l = 0; l < dim; l++) {
                    double ar = aRe[ai + l], aimv = aIm[ai + l];
                    int bidx = l * dim + j;
                    double br = bRe[bidx], bim = bIm[bidx];
                    re += ar * br - aimv * bim;
                    im += ar * bim + aimv * br;
                }
                cRe[i * dim + j] = re; cIm[i * dim + j] = im;
            }
        }
    }

    /** C = A · B†  (B† = conjugate transpose of B). */
    private void mulDagger(double[] aRe, double[] aIm, double[] bRe, double[] bIm,
                           double[] cRe, double[] cIm) {
        for (int i = 0; i < dim; i++) {
            for (int j = 0; j < dim; j++) {
                double re = 0, im = 0;
                int ai = i * dim, bj = j * dim;     // B†[l][j] = conj(B[j][l])
                for (int l = 0; l < dim; l++) {
                    double ar = aRe[ai + l], aimv = aIm[ai + l];
                    double br = bRe[bj + l], bim = -bIm[bj + l];   // conjugate
                    re += ar * br - aimv * bim;
                    im += ar * bim + aimv * br;
                }
                cRe[i * dim + j] = re; cIm[i * dim + j] = im;
            }
        }
    }

    private double oneNorm(double[] re, double[] im) {
        double max = 0;
        for (int j = 0; j < dim; j++) {
            double col = 0;
            for (int i = 0; i < dim; i++) {
                int idx = i * dim + j;
                col += Math.hypot(re[idx], im[idx]);
            }
            if (col > max) max = col;
        }
        return max;
    }
}
