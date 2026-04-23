package ax.xz.mri.service.circuit;

import ax.xz.mri.service.circuit.mna.MnaSolver;

/**
 * Convenience facade over {@link MnaSolver} that preserves the
 * {@code coilDriveI/Q} API the Bloch simulator expects. Each call to
 * {@link #evaluate} solves the MNA system for one pulse step and exposes:
 * per-coil {@code (I, Q)} currents (the "drive" coefficients the Bloch
 * integrator multiplies with each coil's eigenfield) and per-probe
 * {@code (I, Q)} node voltages (the raw observed signal, before
 * downconversion at the probe's carrier).
 *
 * <p>The Bloch integrator doesn't need probe voltages — those are the
 * {@link ax.xz.mri.service.simulation.SignalTraceComputer}'s concern — but
 * since they fall out of the same solve we surface them here for free.
 */
public final class CircuitStepEvaluator {
    private final MnaSolver solver;
    private final MnaSolver.StepOut out;

    public CircuitStepEvaluator(CompiledCircuit circuit) {
        this.solver = new MnaSolver(circuit.mna(), circuit);
        this.out = new MnaSolver.StepOut(circuit.coils().size(), circuit.probes().size());
    }

    /**
     * Solve the circuit for one step. {@code emfReal} and {@code emfImag} may
     * be null for a transmit-only solve (no reciprocity feedback).
     */
    public void evaluate(double[] controls, double dt, double[] emfReal, double[] emfImag,
                         double tSeconds, double omegaSimRadPerSec) {
        solver.step(controls, emfReal, emfImag, dt, tSeconds, omegaSimRadPerSec, out);
    }

    public void resetHistory() { solver.resetHistory(); }

    public double coilDriveI(int coilIndex) { return out.coilIReal()[coilIndex]; }
    public double coilDriveQ(int coilIndex) { return out.coilIImag()[coilIndex]; }

    public double probeVoltageReal(int probeIndex) { return out.probeVReal()[probeIndex]; }
    public double probeVoltageImag(int probeIndex) { return out.probeVImag()[probeIndex]; }
}
