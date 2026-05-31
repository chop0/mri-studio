package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numerical verification of {@link NvClusterEngine} — the clustered quantum
 * NV evolution.
 *
 * <ol>
 *   <li><b>Classical parity</b> — a {@code k = 1} cluster (and any cluster with
 *       zero coupling) reproduces the closed-form Bloch update
 *       ({@code (sx,sy)·e2}, {@code sz → 1 + (sz-1)·e1}) to ≤ 1e-9 across a
 *       Rabi pulse, free precession, and a relaxation window.</li>
 *   <li><b>Flip-flop + entanglement</b> — a close coupled pair transfers
 *       population at the predicted rate {@code sin²(c·t/4)}, conserves total
 *       {@code Sz}, and drives the reduced single-qubit purity below 1
 *       (genuine entanglement). Zero coupling ⇒ no transfer.</li>
 *   <li><b>Physicality</b> — {@code Tr ρ = 1} and every reduced Bloch vector
 *       stays within the unit ball across a long, driven, dissipative run.</li>
 *   <li><b>Magic angle</b> — the secular dipolar prefactor vanishes when the
 *       inter-spin vector sits at {@code cosθ = 1/√3} to the NV axis.</li>
 * </ol>
 */
final class NvClusterEngineTest {

    private static final double GAMMA = 2 * Math.PI * 28.024e9;   // NV electron γ

    @Test
    void kOneReproducesClassicalBloch() {
        var engine = new NvClusterEngine(1, new int[0], new int[0], new double[0], GAMMA);
        double[] st = new double[engine.stateSlots()];
        engine.reset(st, 0);
        assertEquals(4, engine.stateSlots(), "k=1 cluster is a 2×2 density matrix → 4 reals");

        // Reference Bloch vector — starts polarised to ms=0 (sz = +1), exactly
        // as the engine's |0⟩⟨0| reset.
        double rx = 0, ry = 0, rz = 1;
        double dt = 2e-9;

        // Three regimes, each a (Bx,By,Bz,T1,T2) phase the singleton kernel sees.
        double[][] phases = {
            // Rabi about x (no relaxation), 30 steps
            {3e-4, 0, 0, 1.0, 0.1, 30},
            // Free precession about z (detuning), 40 steps
            {0, 0, 2.5e-4, 1.0, 0.1, 40},
            // Off-axis drive + relaxation (pump-like), 25 steps
            {1.2e-4, 0.8e-4, 0.5e-4, 300e-9, 10e-9, 25},
            // Pure relaxation toward |0⟩, 25 steps
            {0, 0, 0, 300e-9, 10e-9, 25},
        };

        for (double[] p : phases) {
            double bx = p[0], by = p[1], bz = p[2];
            double e1 = Math.exp(-dt / p[3]), e2 = Math.exp(-dt / p[4]);
            int steps = (int) p[5];
            for (int s = 0; s < steps; s++) {
                engine.advance(st, 0, new double[]{bx, by, bz}, dt, e1, e2);
                double[] r = blochReference(rx, ry, rz, bx, by, bz, dt, e1, e2);
                rx = r[0]; ry = r[1]; rz = r[2];
                assertEquals(rx, engine.sx(st, 0, 0), 1e-9, "sx parity");
                assertEquals(ry, engine.sy(st, 0, 0), 1e-9, "sy parity");
                assertEquals(rz, engine.sz(st, 0, 0), 1e-9, "sz parity");
            }
        }
    }

    @Test
    void closePairFlipFlopsAtPredictedRateAndEntangles() {
        double c = 2 * Math.PI * 50e3;             // 50 kHz secular dipolar coupling
        var engine = pair(c);
        double[] st = new double[engine.stateSlots()];
        engine.reset(st, 0);
        excite(st, 0, 0b01);                        // |q0=1, q1=0⟩ — NV0 excited, NV1 ground

        assertEquals(-1.0, engine.sz(st, 0, 0), 1e-12, "NV0 starts excited");
        assertEquals(+1.0, engine.sz(st, 0, 1), 1e-12, "NV1 starts in ms=0");
        assertEquals(1.0, purity(engine, st, 0), 1e-9, "Product state starts pure (separable)");

        // Evolve to the full-transfer time t = 2π/c (P_transfer = sin²(c·t/4)),
        // sampling the half-transfer milestone at t = π/c.
        double tFull = 2 * Math.PI / c;
        int n = 400;
        double dt = tFull / n;
        double[] zero3 = new double[6];             // no Zeeman field on either member
        double minSzTotal = Double.MAX_VALUE, maxSzTotal = -Double.MAX_VALUE;

        for (int s = 1; s <= n; s++) {
            engine.advance(st, 0, zero3, dt, 1.0, 1.0);   // coherent: no relaxation
            double t = s * dt;
            double pTransfer = st[2];               // diagonal population of basis |q0=0,q1=1⟩
            assertEquals(Math.sin(c * t / 4) * Math.sin(c * t / 4), pTransfer, 0.02,
                "Population transfer must follow sin²(c·t/4)");
            double szTotal = engine.sz(st, 0, 0) + engine.sz(st, 0, 1);
            minSzTotal = Math.min(minSzTotal, szTotal);
            maxSzTotal = Math.max(maxSzTotal, szTotal);

            if (s == n / 2) {                        // t = π/c: equal superposition
                assertEquals(0.5, pTransfer, 0.02, "Half transfer at t = π/c");
                assertEquals(0.0, engine.sz(st, 0, 0), 0.05, "NV0 ⟨σz⟩ → 0 at half transfer");
                assertTrue(purity(engine, st, 0) < 0.6,
                    "Reduced purity drops well below 1 — the pair is entangled");
            }
        }
        assertEquals(1.0, st[2], 0.03, "Near-complete population transfer at t = 2π/c");
        assertEquals(0.0, minSzTotal, 1e-9, "Flip-flop conserves total Sz (min)");
        assertEquals(0.0, maxSzTotal, 1e-9, "Flip-flop conserves total Sz (max)");
    }

    @Test
    void zeroCouplingGivesNoTransfer() {
        var engine = pair(0.0);
        double[] st = new double[engine.stateSlots()];
        engine.reset(st, 0);
        excite(st, 0, 0b01);
        double[] zero3 = new double[6];
        for (int s = 0; s < 400; s++) engine.advance(st, 0, zero3, 1e-8, 1.0, 1.0);
        assertEquals(0.0, st[2], 1e-9, "No coupling ⇒ no population transfer");
        assertEquals(-1.0, engine.sz(st, 0, 0), 1e-9, "NV0 stays excited");
        assertEquals(1.0, purity(engine, st, 0), 1e-9, "Uncoupled qubit stays pure");
    }

    @Test
    void traceAndBlochNormStayValidOverLongDrivenRun() {
        double c = 2 * Math.PI * 80e3;
        var engine = pair(c);
        double[] st = new double[engine.stateSlots()];
        engine.reset(st, 0);
        double dt = 4e-9;
        // Different detunings per member + relaxation: a generic mixed, driven run.
        double[] localB = {3e-4, 0, 1e-4, 0, 1.5e-4, -2e-4};
        double e1 = Math.exp(-dt / 300e-9), e2 = Math.exp(-dt / 10e-9);
        for (int s = 0; s < 2000; s++) {
            engine.advance(st, 0, localB, dt, e1, e2);
            double trace = st[0] + st[1] + st[2] + st[3];     // packed diagonal of the 4×4 ρ
            assertEquals(1.0, trace, 1e-9, "Tr ρ must stay 1");
            for (int q = 0; q < 2; q++) {
                double sx = engine.sx(st, 0, q), sy = engine.sy(st, 0, q), sz = engine.sz(st, 0, q);
                double norm = Math.sqrt(sx * sx + sy * sy + sz * sz);
                assertTrue(norm <= 1.0 + 1e-9,
                    "Reduced Bloch vector must stay inside the unit ball (got " + norm + ")");
            }
        }
    }

    @Test
    void magicAngleDipolarVanishes() {
        Vec3 axis = new Vec3(0, 0, 1);
        Vec3 a = new Vec3(0, 0, 0);
        double onAxis = Math.abs(NvDipolar.couplingRadPerSec(a, new Vec3(0, 0, 20e-9), axis, GAMMA));
        // Magic angle: cosθ = 1/√3 → 1 − 3cos²θ = 0.
        double cos = 1.0 / Math.sqrt(3.0), sin = Math.sqrt(1 - cos * cos), r = 20e-9;
        double magic = Math.abs(NvDipolar.couplingRadPerSec(
            a, new Vec3(r * sin, 0, r * cos), axis, GAMMA));
        assertTrue(onAxis > 1e3, "On-axis coupling is appreciable (got " + onAxis + " rad/s)");
        assertTrue(magic < onAxis * 1e-6, "Coupling vanishes at the magic angle (got " + magic + " rad/s)");
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static NvClusterEngine pair(double c) {
        return new NvClusterEngine(2, new int[]{0}, new int[]{1}, new double[]{c}, GAMMA);
    }

    /** Overwrite the packed density matrix with the pure basis state |basis⟩. */
    private static void excite(double[] st, int off, int basis) {
        for (int i = 0; i < 16; i++) st[off + i] = 0.0;   // dim=4 → 16 reals
        st[off + basis] = 1.0;
    }

    /** Reduced single-qubit purity Tr(ρ_q²) = ½(1 + |⟨σ⟩|²). */
    private static double purity(NvClusterEngine e, double[] st, int q) {
        double sx = e.sx(st, 0, q), sy = e.sy(st, 0, q), sz = e.sz(st, 0, q);
        return 0.5 * (1 + sx * sx + sy * sy + sz * sz);
    }

    /**
     * The closed-form Bloch update the singleton kernel applies — Rodrigues
     * rotation about the local field by {@code γ|B|dt}, then T₁/T₂ relaxation.
     * Mirrors {@code NvKernel.advanceSingleton} exactly so it is the parity
     * reference.
     */
    private static double[] blochReference(double sx, double sy, double sz,
                                           double bx, double by, double bz,
                                           double dt, double e1, double e2) {
        double bmag2 = bx * bx + by * by + bz * bz;
        if (bmag2 < 1e-30) {
            return new double[]{sx * e2, sy * e2, 1.0 + (sz - 1.0) * e1};
        }
        double bm = Math.sqrt(bmag2);
        double nx = bx / bm, ny = by / bm, nz = bz / bm;
        double th = GAMMA * bm * dt;
        double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
        double nd = nx * sx + ny * sy + nz * sz;
        double cx = ny * sz - nz * sy, cy = nz * sx - nx * sz, cz = nx * sy - ny * sx;
        return new double[]{
            (sx * c + cx * s + nx * nd * omc) * e2,
            (sy * c + cy * s + ny * nd * omc) * e2,
            1.0 + (sz * c + cz * s + nz * nd * omc - 1.0) * e1,
        };
    }
}
