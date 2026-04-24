package ax.xz.mri.service.circuit.mna;

import ax.xz.mri.model.circuit.compile.CtlBinding;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.service.circuit.CompiledCircuit;

import java.util.Arrays;

/**
 * Per-simulation Modified Nodal Analysis solver.
 *
 * <p>The {@link MnaNetwork} fixes topology at compile time; this class walks
 * each timestep:
 * <ol>
 *   <li>Resolves each switch's {@code ctl} to closed/open from the source
 *       control vector.</li>
 *   <li>Stamps the conductance matrix {@code G} and RHS {@code b} for the
 *       current step, using backward-Euler forms for L and C so the integration
 *       is stable at the simulator's dt.</li>
 *   <li>LU-factorises {@code G} and solves twice: once for the I-channel
 *       (real part of source drives and coil EMFs) and once for the Q-channel
 *       (imaginary part). QUADRATURE sources split their control into I/Q;
 *       REAL and STATIC contribute only to the I solve.</li>
 *   <li>Writes per-coil currents and per-probe node voltages into
 *       {@link StepOut}, then updates its inductor/capacitor history.</li>
 * </ol>
 *
 * <p>Voltage branches occupy rows {@code nodeCount..nodeCount+voltageBranchCount-1}
 * of the system. A coil's branch equation is
 * {@code V_a − (R + L/dt)·I = EMF − (L/dt)·I_prev}; a source's is simply
 * {@code V_a − V_b = V_source}. Capacitors stamp a Norton equivalent directly
 * into the conductance block (no branch row).
 */
public final class MnaSolver {
    private final MnaNetwork net;
    private final CompiledCircuit circuit;
    private final int size;

    private final double[][] G;
    private final double[] bI, bQ;
    private final double[] xI, xQ;

    // Working LU state — shared across the two I/Q solves each step.
    private final double[][] lu;
    private final int[] piv;

    // Backward-Euler history — one entry per voltage branch (coil or passive
    // inductor; pure source branches don't care but we size the arrays the
    // same way for simplicity).
    private final double[] prevBranchIReal, prevBranchIImag;
    private final double[] prevCapVReal, prevCapVImag;

    public MnaSolver(MnaNetwork net, CompiledCircuit circuit) {
        this.net = net;
        this.circuit = circuit;
        this.size = net.systemSize();
        this.G = new double[size][size];
        this.bI = new double[size];
        this.bQ = new double[size];
        this.xI = new double[size];
        this.xQ = new double[size];
        this.lu = new double[size][size];
        this.piv = new int[size];
        this.prevBranchIReal = new double[net.branchCount()];
        this.prevBranchIImag = new double[net.branchCount()];
        this.prevCapVReal = new double[net.capacitorCount()];
        this.prevCapVImag = new double[net.capacitorCount()];
    }

    public int coilCount() { return net.coilCount(); }
    public int probeCount() { return net.probeCount(); }

    /**
     * Solve the circuit for one time step of length {@code dt} seconds at
     * simulation time {@code tSeconds}.
     *
     * @param controls     per-step pulse-sequence control vector (length =
     *                     {@link CompiledCircuit#totalChannelCount()}).
     * @param emfReal      real-channel reciprocity EMF per coil, or
     *                     {@code null} to treat all EMFs as zero (transmit-
     *                     only solve).
     * @param emfImag      imaginary-channel EMF per coil, or {@code null}.
     * @param tSeconds     current simulation time; used for QUADRATURE source
     *                     off-resonance rotation.
     * @param omegaSimRadPerSec the rotating-frame reference {@code γ·B0ref};
     *                          same as above.
     * @param out          filled with per-coil currents and per-probe voltages.
     */
    public void step(double[] controls, double[] emfReal, double[] emfImag,
                     double dt, double tSeconds, double omegaSimRadPerSec,
                     StepOut out) {
        boolean[] closed = resolveSwitchClosures(controls);
        stampG(dt, closed);

        // Mixer RHS is a function of V_in, which itself is part of the
        // solution vector. We use a fixed-point iteration: start with V_out
        // pinned to zero, solve, read V_in at each mixer, recompute V_out,
        // solve again. For any DAG topology (mixer inputs aren't downstream
        // of their own outputs) one re-solve converges; a second pass is
        // essentially free insurance against stiff cases.
        double[] mixerVoutRe = new double[net.mixerCount()];
        double[] mixerVoutIm = new double[net.mixerCount()];
        stampB(controls, emfReal, emfImag, dt, tSeconds, omegaSimRadPerSec,
            mixerVoutRe, mixerVoutIm);
        copyMatrix(G, lu);
        luFactorize(lu, size, piv);
        luSolve(lu, size, piv, bI, xI);
        luSolve(lu, size, piv, bQ, xQ);
        if (net.mixerCount() > 0) {
            int passes = 2;
            for (int pass = 0; pass < passes; pass++) {
                boolean converged = updateMixerOutputs(mixerVoutRe, mixerVoutIm, tSeconds);
                stampB(controls, emfReal, emfImag, dt, tSeconds, omegaSimRadPerSec,
                    mixerVoutRe, mixerVoutIm);
                luSolve(lu, size, piv, bI, xI);
                luSolve(lu, size, piv, bQ, xQ);
                if (converged) break;
            }
        }

        extractResults(out);
        updateHistory(controls);
    }

    /**
     * Fill {@code mixerVout*} from the current node-voltage solution by
     * applying each mixer's {@code exp(-j·2π·loHz·t)} rotation to its
     * input-node voltage. Returns {@code true} if every entry is within
     * 1e-12 of its previous value (fixed-point reached).
     */
    private boolean updateMixerOutputs(double[] voutRe, double[] voutIm, double tSeconds) {
        boolean converged = true;
        for (int m = 0; m < net.mixerCount(); m++) {
            int inNode = net.mixerInNode()[m];
            double vInRe = inNode >= 0 ? xI[inNode] : 0;
            double vInIm = inNode >= 0 ? xQ[inNode] : 0;
            double theta = 2 * Math.PI * net.mixerLoHz()[m] * tSeconds;
            double c = Math.cos(theta), s = Math.sin(theta);
            // V_out = V_in · e^{-jθ} = (Re + j·Im)·(cos − j·sin)
            //      = Re·cos + Im·sin + j·(Im·cos − Re·sin)
            double newRe = vInRe * c + vInIm * s;
            double newIm = vInIm * c - vInRe * s;
            if (Math.abs(newRe - voutRe[m]) > 1e-12) converged = false;
            if (Math.abs(newIm - voutIm[m]) > 1e-12) converged = false;
            voutRe[m] = newRe;
            voutIm[m] = newIm;
        }
        return converged;
    }

    /** Reset backward-Euler history to zero (call at the start of a new trajectory). */
    public void resetHistory() {
        Arrays.fill(prevBranchIReal, 0);
        Arrays.fill(prevBranchIImag, 0);
        Arrays.fill(prevCapVReal, 0);
        Arrays.fill(prevCapVImag, 0);
    }

    // ───────── Stamping ─────────

    private boolean[] resolveSwitchClosures(double[] controls) {
        int n = net.switchCount();
        boolean[] closed = new boolean[n];
        for (int i = 0; i < n; i++) {
            var ctl = net.switchCtl()[i];
            Double v = switch (ctl) {
                case CtlBinding.AlwaysOpen a -> null;
                case CtlBinding.FromSourceOut ref ->
                    sourceOutValue(controls, circuit.sources().get(ref.sourceIndex()));
                case CtlBinding.FromSourceActive ref ->
                    sourceActiveValue(controls, circuit.sources().get(ref.sourceIndex())) ? 1.0 : 0.0;
            };
            if (v == null) { closed[i] = false; continue; }
            boolean raw = v >= net.switchThreshold()[i];
            closed[i] = raw ^ net.switchInvert()[i];
        }
        return closed;
    }

    private void stampG(double dt, boolean[] switchClosed) {
        for (double[] row : G) Arrays.fill(row, 0);

        // Fixed resistors.
        for (int i = 0; i < net.resistorCount(); i++) {
            stampResistor(G, net.resistorA()[i], net.resistorB()[i], net.resistorConductance()[i]);
        }

        // Capacitors (backward Euler) contribute a conductance of C/dt.
        for (int i = 0; i < net.capacitorCount(); i++) {
            double g = net.capacitorFarads()[i] / dt;
            stampResistor(G, net.capacitorA()[i], net.capacitorB()[i], g);
        }

        // Switches (including mux pseudo-switches) are conductances chosen by ctl state.
        for (int i = 0; i < net.switchCount(); i++) {
            double r = switchClosed[i] ? net.switchClosedOhms()[i] : net.switchOpenOhms()[i];
            stampResistor(G, net.switchA()[i], net.switchB()[i], 1.0 / r);
        }

        // Voltage branches (source-out, source-active, coil) each add one row and column.
        for (int b = 0; b < net.branchCount(); b++) {
            int branchRow = net.nodeCount() + b;
            int a = net.branchNodeA()[b];
            int bn = net.branchNodeB()[b];
            // Incidence: +1 at node a, -1 at node b in the branch column.
            if (a >= 0) {
                G[a][branchRow] += 1;
                G[branchRow][a] += 1;
            }
            if (bn >= 0) {
                G[bn][branchRow] -= 1;
                G[branchRow][bn] -= 1;
            }
            // Branch equation diagonal:
            //   source-*: 0  (pure imposed voltage: V_a - V_b = V_s)
            //   coil / passive-inductor: -(R + L/dt) (backward-Euler impedance)
            double r = net.branchR()[b];
            double l = net.branchL()[b];
            if (r != 0 || l != 0) {
                G[branchRow][branchRow] = -(r + l / dt);
            }
        }
    }

    private static void stampResistor(double[][] M, int a, int b, double g) {
        if (a >= 0) M[a][a] += g;
        if (b >= 0) M[b][b] += g;
        if (a >= 0 && b >= 0) {
            M[a][b] -= g;
            M[b][a] -= g;
        }
    }

    private void stampB(double[] controls, double[] emfReal, double[] emfImag,
                        double dt, double tSeconds, double omegaSimRadPerSec,
                        double[] mixerVoutRe, double[] mixerVoutIm) {
        Arrays.fill(bI, 0);
        Arrays.fill(bQ, 0);

        // Capacitor history: a current source of (C/dt)·V_prev into node a, out of node b.
        for (int i = 0; i < net.capacitorCount(); i++) {
            double g = net.capacitorFarads()[i] / dt;
            int a = net.capacitorA()[i];
            int b = net.capacitorB()[i];
            if (a >= 0) {
                bI[a] += g * prevCapVReal[i];
                bQ[a] += g * prevCapVImag[i];
            }
            if (b >= 0) {
                bI[b] -= g * prevCapVReal[i];
                bQ[b] -= g * prevCapVImag[i];
            }
        }

        // Voltage branch RHS.
        for (int b = 0; b < net.branchCount(); b++) {
            int branchRow = net.nodeCount() + b;
            int ref = net.branchRefIndex()[b];
            switch (net.branchKind()[b]) {
                case SOURCE_OUT -> {
                    var src = circuit.sources().get(ref);
                    double[] iq = sourceOutIQ(controls, src, tSeconds, omegaSimRadPerSec);
                    bI[branchRow] = iq[0];
                    bQ[branchRow] = iq[1];
                }
                case METADATA_OUT -> {
                    // ref is the metadata index. Look up its source and mode.
                    int srcIdx = net.metadataSourceIndex()[ref];
                    double v = 0;
                    if (srcIdx >= 0) {
                        var mode = net.metadataMode()[ref];
                        v = switch (mode) {
                            case ACTIVE -> sourceActiveValue(controls, circuit.sources().get(srcIdx)) ? 1.0 : 0.0;
                        };
                    }
                    bI[branchRow] = v;
                    bQ[branchRow] = 0;
                }
                case COIL, PASSIVE_INDUCTOR -> {
                    double l = net.branchL()[b];
                    double hist = -(l / dt);
                    double re = hist * prevBranchIReal[b];
                    double im = hist * prevBranchIImag[b];
                    if (net.branchKind()[b] == MnaNetwork.VBranchKind.COIL) {
                        if (emfReal != null) re += emfReal[ref];
                        if (emfImag != null) im += emfImag[ref];
                    }
                    bI[branchRow] = re;
                    bQ[branchRow] = im;
                }
                case MIXER_OUT -> {
                    // The mixer stamps a voltage branch V_out − 0 = V_out_value;
                    // the value comes from the current iteration's snapshot of
                    // V_in · exp(−j·2π·loHz·t). First pass has mixerVout* = 0
                    // (no-op), then updateMixerOutputs() refreshes from V_in.
                    bI[branchRow] = mixerVoutRe[ref];
                    bQ[branchRow] = mixerVoutIm[ref];
                }
            }
        }
    }

    private static double sourceOutValue(double[] controls, CompiledCircuit.CompiledSource src) {
        return switch (src.kind()) {
            case STATIC -> src.staticAmplitude();
            case REAL, GATE -> controls[src.channelOffset()];
            case QUADRATURE -> Math.hypot(
                controls[src.channelOffset()], controls[src.channelOffset() + 1]);
        };
    }

    private static double[] sourceOutIQ(double[] controls, CompiledCircuit.CompiledSource src,
                                         double tSeconds, double omegaSimRadPerSec) {
        return switch (src.kind()) {
            case STATIC -> new double[]{src.staticAmplitude(), 0};
            case REAL, GATE -> new double[]{controls[src.channelOffset()], 0};
            case QUADRATURE -> {
                double i = controls[src.channelOffset()];
                double q = controls[src.channelOffset() + 1];
                double deltaOmega = 2 * Math.PI * src.carrierHz() - omegaSimRadPerSec;
                if (Math.abs(deltaOmega) > 1e-9) {
                    double phase = deltaOmega * tSeconds;
                    double c = Math.cos(phase), s = Math.sin(phase);
                    yield new double[]{i * c - q * s, i * s + q * c};
                }
                yield new double[]{i, q};
            }
        };
    }

    private static boolean sourceActiveValue(double[] controls, CompiledCircuit.CompiledSource src) {
        if (src.kind() == AmplitudeKind.STATIC) return src.staticAmplitude() != 0.0;
        int count = src.kind().channelCount();
        int offset = src.channelOffset();
        for (int k = 0; k < count; k++) {
            if (controls[offset + k] != 0.0) return true;
        }
        return false;
    }

    // ───────── LU ─────────

    private static void copyMatrix(double[][] src, double[][] dst) {
        int n = src.length;
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, dst[i], 0, n);
    }

    /** In-place LU factorisation with partial pivoting. */
    private static void luFactorize(double[][] A, int n, int[] piv) {
        for (int i = 0; i < n; i++) piv[i] = i;
        for (int k = 0; k < n; k++) {
            int p = k;
            double max = Math.abs(A[k][k]);
            for (int i = k + 1; i < n; i++) {
                double v = Math.abs(A[i][k]);
                if (v > max) { max = v; p = i; }
            }
            if (max == 0) throw new IllegalStateException(
                "Singular MNA matrix at pivot " + k + "; check for floating nodes or zero-impedance loops.");
            if (p != k) {
                double[] tmp = A[k]; A[k] = A[p]; A[p] = tmp;
                int t = piv[k]; piv[k] = piv[p]; piv[p] = t;
            }
            double pivot = A[k][k];
            for (int i = k + 1; i < n; i++) {
                A[i][k] /= pivot;
                double factor = A[i][k];
                for (int j = k + 1; j < n; j++) A[i][j] -= factor * A[k][j];
            }
        }
    }

    private static void luSolve(double[][] LU, int n, int[] piv, double[] b, double[] x) {
        for (int i = 0; i < n; i++) x[i] = b[piv[i]];
        for (int i = 1; i < n; i++) {
            double s = x[i];
            for (int j = 0; j < i; j++) s -= LU[i][j] * x[j];
            x[i] = s;
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = x[i];
            for (int j = i + 1; j < n; j++) s -= LU[i][j] * x[j];
            x[i] = s / LU[i][i];
        }
    }

    // ───────── Extraction ─────────

    private void extractResults(StepOut out) {
        for (int c = 0; c < net.coilCount(); c++) {
            int branchRow = net.nodeCount() + net.coilBranch()[c];
            out.coilIReal()[c] = xI[branchRow];
            out.coilIImag()[c] = xQ[branchRow];
        }
        for (int p = 0; p < net.probeCount(); p++) {
            int node = net.probeNode()[p];
            if (node < 0) {
                out.probeVReal()[p] = 0;
                out.probeVImag()[p] = 0;
            } else {
                out.probeVReal()[p] = xI[node];
                out.probeVImag()[p] = xQ[node];
            }
        }
    }

    private void updateHistory(double[] controls) {
        // Inductor current history for every voltage branch (source branches
        // end up storing their branch currents too, harmlessly).
        for (int b = 0; b < net.branchCount(); b++) {
            int branchRow = net.nodeCount() + b;
            prevBranchIReal[b] = xI[branchRow];
            prevBranchIImag[b] = xQ[branchRow];
        }
        // Capacitor voltage history.
        for (int i = 0; i < net.capacitorCount(); i++) {
            int a = net.capacitorA()[i];
            int b = net.capacitorB()[i];
            double vAI = a >= 0 ? xI[a] : 0;
            double vBI = b >= 0 ? xI[b] : 0;
            double vAQ = a >= 0 ? xQ[a] : 0;
            double vBQ = b >= 0 ? xQ[b] : 0;
            prevCapVReal[i] = vAI - vBI;
            prevCapVImag[i] = vAQ - vBQ;
        }
    }

    /** Output buffers for one solver step — preallocated once per simulation. */
    public static final class StepOut {
        private final double[] coilIReal, coilIImag;
        private final double[] probeVReal, probeVImag;

        public StepOut(int nCoils, int nProbes) {
            coilIReal = new double[nCoils];
            coilIImag = new double[nCoils];
            probeVReal = new double[nProbes];
            probeVImag = new double[nProbes];
        }

        public double[] coilIReal() { return coilIReal; }
        public double[] coilIImag() { return coilIImag; }
        public double[] probeVReal() { return probeVReal; }
        public double[] probeVImag() { return probeVImag; }
    }
}
