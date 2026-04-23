package ax.xz.mri.service.circuit;

import ax.xz.mri.model.simulation.AmplitudeKind;

/**
 * Per-step helper: given a {@link CompiledCircuit} and a pulse step's raw
 * control array, compute the effective {@code (I, Q)} drive on each coil and
 * the gating factor each observe link needs.
 *
 * <p>A single {@code CircuitStepEvaluator} is instantiated once per
 * simulation and reused across every step; it owns small scratch arrays
 * sized by the circuit's source / switch / coil counts.
 */
public final class CircuitStepEvaluator {
    private final CompiledCircuit compiled;
    private final double[] sourceValues;
    private final boolean[] switchClosed;
    private final double[] coilDriveI;
    private final double[] coilDriveQ;

    public CircuitStepEvaluator(CompiledCircuit compiled) {
        this.compiled = compiled;
        this.sourceValues = new double[compiled.sources().size()];
        this.switchClosed = new boolean[compiled.switches().size()];
        this.coilDriveI = new double[compiled.coils().size()];
        this.coilDriveQ = new double[compiled.coils().size()];
    }

    /**
     * Populate the per-coil drive arrays for this step. {@code tSeconds} is
     * used to rotate off-resonance QUADRATURE sources into the rotating frame;
     * {@code omegaSimRadPerSec} is the rotating-frame reference frequency
     * ({@code γ·B0ref}).
     */
    public void evaluate(double[] controls, double tSeconds, double omegaSimRadPerSec) {
        for (int i = 0; i < sourceValues.length; i++) {
            var src = compiled.sources().get(i);
            sourceValues[i] = switch (src.kind()) {
                case STATIC -> src.staticAmplitude();
                case REAL, GATE -> controls[src.channelOffset()];
                // QUADRATURE returns the I-like magnitude for threshold logic;
                // actual (I, Q) handled inside the drive-link loop below.
                case QUADRATURE -> Math.hypot(controls[src.channelOffset()], controls[src.channelOffset() + 1]);
            };
        }
        for (int i = 0; i < switchClosed.length; i++) {
            var sw = compiled.switches().get(i);
            if (sw.ctlSourceIndex() < 0) {
                switchClosed[i] = false;
            } else {
                double ctl = sourceValues[sw.ctlSourceIndex()];
                switchClosed[i] = ctl >= sw.thresholdVolts();
            }
        }
        java.util.Arrays.fill(coilDriveI, 0);
        java.util.Arrays.fill(coilDriveQ, 0);
        for (var link : compiled.drives()) {
            if (!allClosed(link.switchIndices())) continue;
            var src = compiled.sources().get(link.endpointIndex());
            double sign = link.forwardPolarity() ? 1.0 : -1.0;
            if (src.kind() == AmplitudeKind.QUADRATURE) {
                double i = controls[src.channelOffset()];
                double q = controls[src.channelOffset() + 1];
                double deltaOmega = 2 * Math.PI * src.carrierHz() - omegaSimRadPerSec;
                if (Math.abs(deltaOmega) > 1e-9) {
                    double phase = deltaOmega * tSeconds;
                    double c = Math.cos(phase), s = Math.sin(phase);
                    double ir = i * c - q * s;
                    double qr = i * s + q * c;
                    i = ir;
                    q = qr;
                }
                coilDriveI[link.coilIndex()] += sign * i;
                coilDriveQ[link.coilIndex()] += sign * q;
            } else if (src.kind() == AmplitudeKind.REAL) {
                coilDriveI[link.coilIndex()] += sign * controls[src.channelOffset()];
            }
            // STATIC sources are folded into staticBz at bake time; the per-step
            // coil-drive path must not re-apply them.
            // GATE sources don't appear in drives (compiler filtered them out).
        }
    }

    public double coilDriveI(int coilIndex) { return coilDriveI[coilIndex]; }
    public double coilDriveQ(int coilIndex) { return coilDriveQ[coilIndex]; }
    public boolean switchClosed(int switchIndex) { return switchClosed[switchIndex]; }

    /** Whether an observe or drive link is live this step — product of its switch closures. */
    public boolean allClosed(java.util.List<Integer> switchIndices) {
        for (int idx : switchIndices) if (!switchClosed[idx]) return false;
        return true;
    }
}
