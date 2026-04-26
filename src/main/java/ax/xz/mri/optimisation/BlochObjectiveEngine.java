package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;
import ax.xz.mri.service.simulation.math.BlochStep;
import ax.xz.mri.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/** Shared Bloch-physics objective implementation for Java optimiser backends. */
public abstract class BlochObjectiveEngine implements ObjectiveEngine {
    protected static final double DEFAULT_EPSILON_FACTOR = 1e-4;

    @Override
    public ObjectiveEvaluation evaluate(OptimisationProblem problem, List<PulseSegment> segments) {
        return evaluateInternal(problem, segments, true);
    }

    @Override
    public SignalTrace simulateSignal(OptimisationProblem problem, List<PulseSegment> segments) {
        return evaluateInternal(problem, segments, true).signalTrace();
    }

    protected ObjectiveEvaluation evaluateInternal(OptimisationProblem problem, List<PulseSegment> segments, boolean captureSignal) {
        validateSegments(problem.sequenceTemplate(), segments);
        return switch (problem.objectiveSpec().mode()) {
            case FULL_TRAIN -> evaluateFullTrain(problem, segments, captureSignal);
            case PERIODIC_CYCLE -> evaluatePeriodicCycle(problem, segments, captureSignal);
        };
    }

    private ObjectiveEvaluation evaluateFullTrain(OptimisationProblem problem, List<PulseSegment> segments, boolean captureSignal) {
        var geometry = problem.geometry();
        var objective = problem.objectiveSpec();
        var circuit = geometry.circuit();
        int primaryIdx = geometry.primaryProbeIndex();
        var mx = geometry.mx0().clone();
        var my = geometry.my0().clone();
        var mz = geometry.mz0().clone();
        var evaluator = new CircuitStepEvaluator(circuit);
        double omegaSim = geometry.omegaSim();
        double timeMicros = 0.0;
        double tSeconds = 0.0;

        int nCoils = circuit.coils().size();
        double[] sCoilRePrev = new double[nCoils];
        double[] sCoilImPrev = new double[nCoils];
        double[] emfRe = new double[nCoils];
        double[] emfIm = new double[nCoils];

        var signalPoints = captureSignal ? new ArrayList<SignalTrace.Point>() : null;
        if (captureSignal) {
            signalPoints.add(new SignalTrace.Point(0.0, 0.0, 0.0));
        }

        double jIn = 0.0;
        double jOut = 0.0;
        double powerOut = 0.0;
        double rfPower = 0.0;
        double rfSmooth = 0.0;
        double gateSwitch = 0.0;
        double gateBinary = 0.0;
        double totalDt = 0.0;
        int[] rfOffsets = circuit.rfEnvelopeChannelOffsets();
        int quadChannels = rfOffsets.length;

        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var spec = problem.sequenceTemplate().segments().get(segmentIndex);
            double[][] prevQuad = new double[2][quadChannels];
            double prevGate = 0.0;
            boolean haveGate = false;
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                var step = segment.steps().get(stepIndex);
                double dt = spec.dt();
                totalDt += dt;
                // Reciprocity coupling integrals from current M → EMF feedback.
                computeEmfs(geometry, mx, my, dt, sCoilRePrev, sCoilImPrev, emfRe, emfIm);
                evaluator.evaluate(step.controls(), dt, emfRe, emfIm, tSeconds, omegaSim);
                applyStep(geometry, evaluator, dt, mx, my, mz);
                double sigGate = 1.0 - MathUtil.clamp(step.rfGate(), 0.0, 1.0);
                double[] signal = probeSignal(geometry, primaryIdx, evaluator, tSeconds);
                double sigMag2 = signal[0] * signal[0] + signal[1] * signal[1];
                double sxOut = weightedSum(geometry.wOut(), mx);
                double syOut = weightedSum(geometry.wOut(), my);
                double powerOutStep = weightedPower(geometry.wOut(), mx, my);
                jIn += sigGate * sigMag2;
                jOut += sigGate * (sxOut * sxOut + syOut * syOut);
                powerOut += sigGate * powerOutStep;
                double[] quadNow = rfChannelValues(step, rfOffsets);
                rfPower += sumOfSquares(quadNow) * dt;
                if (stepIndex >= 2) {
                    double smooth = 0.0;
                    for (int k = 0; k < quadChannels; k++) {
                        double d2 = quadNow[k] - 2.0 * prevQuad[0][k] + prevQuad[1][k];
                        smooth += d2 * d2;
                    }
                    rfSmooth += smooth * dt;
                }
                if (haveGate) {
                    double dg = step.rfGate() - prevGate;
                    gateSwitch += dg * dg * dt;
                }
                gateBinary += step.rfGate() * (1.0 - step.rfGate()) * dt;
                for (int k = 0; k < quadChannels; k++) {
                    prevQuad[1][k] = prevQuad[0][k];
                    prevQuad[0][k] = quadNow[k];
                }
                prevGate = step.rfGate();
                haveGate = true;
                timeMicros += dt * 1e6;
                tSeconds += dt;
                if (captureSignal) {
                    signalPoints.add(new SignalTrace.Point(timeMicros, sigGate * signal[0], sigGate * signal[1]));
                }
            }
        }

        double signalRef = Math.max(probeReferenceSquared(geometry), 1.0);
        double powerRef = Math.max(primaryCoilSensitivityMax(geometry), 1.0);
        double rfTimeRef = Math.max(totalDt, 1e-12);
        double rfRef = referenceRfPower(circuit) * rfTimeRef;
        double rfPowerRef = Math.max(rfRef, 1e-30);

        double value = -(
            jIn / signalRef
                - objective.lamOut() * (jOut / signalRef)
                - objective.lamPow() * (powerOut / powerRef)
                - objective.rfPenalty() * (rfPower / rfPowerRef)
                - objective.rfSmoothPenalty() * (rfSmooth / rfPowerRef)
                - objective.gateSwitchPenalty() * (gateSwitch / rfTimeRef)
                - objective.gateBinaryPenalty() * (gateBinary / rfTimeRef)
        );
        return new ObjectiveEvaluation(value, captureSignal ? new SignalTrace(List.copyOf(signalPoints)) : null);
    }

    private ObjectiveEvaluation evaluatePeriodicCycle(OptimisationProblem problem, List<PulseSegment> segments, boolean captureSignal) {
        // Periodic-cycle steady-state uses the linear step matrix (purely diagonal in B) for each point.
        // With circuits, the step matrix depends on the circuit evaluation at each step; computing the
        // cycle transfer matrix requires re-evaluating across all steps. Fall back to full-train semantics.
        return evaluateFullTrain(problem, segments, captureSignal);
    }

    protected static void validateSegments(SequenceTemplate template, List<PulseSegment> segments) {
        if (segments.size() != template.segments().size())
            throw new IllegalArgumentException("segment count does not match sequence template");
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).steps().size() != template.segments().get(index).totalSteps())
                throw new IllegalArgumentException("step count does not match sequence template at segment " + index);
        }
    }

    /**
     * Apply one Bloch step to every point using the pre-evaluated per-coil
     * drives from {@code evaluator}.
     */
    protected static void applyStep(ProblemGeometry geometry, CircuitStepEvaluator evaluator, double dt,
                                    double[] mx, double[] my, double[] mz) {
        double gamma = geometry.gamma();
        double e2 = Math.exp(-dt / geometry.t2());
        double e1 = Math.exp(-dt / geometry.t1());
        int nCoils = geometry.circuit().coils().size();
        int nPoints = geometry.pointCount();
        double[][] ex = geometry.coilExFlat();
        double[][] ey = geometry.coilEyFlat();
        double[][] ez = geometry.coilEzFlat();
        double[] staticBz = geometry.staticBz();

        double[] iDrive = new double[nCoils];
        double[] qDrive = new double[nCoils];
        for (int c = 0; c < nCoils; c++) {
            iDrive[c] = evaluator.coilDriveI(c);
            qDrive[c] = evaluator.coilDriveQ(c);
        }

        for (int p = 0; p < nPoints; p++) {
            double bx = 0, by = 0, bz = staticBz[p];
            for (int c = 0; c < nCoils; c++) {
                double exp = ex[c][p], eyp = ey[c][p], ezp = ez[c][p];
                bx += iDrive[c] * exp - qDrive[c] * eyp;
                by += iDrive[c] * eyp + qDrive[c] * exp;
                bz += iDrive[c] * ezp;
            }
            var next = (bx * bx + by * by) < BlochStep.B_PERP_SQ_FLOOR
                ? BlochStep.zOnly(bz, gamma, dt, e1, e2, mx[p], my[p], mz[p])
                : BlochStep.rodrigues(bx, by, bz, gamma, dt, e1, e2, mx[p], my[p], mz[p]);
            mx[p] = next.mx();
            my[p] = next.my();
            mz[p] = next.mz();
        }
    }

    /** Snapshot of the compiled RF envelope slots at the current step. */
    protected static double[] rfChannelValues(PulseStep step, int[] rfOffsets) {
        double[] out = new double[rfOffsets.length];
        var controls = step.controls();
        for (int i = 0; i < rfOffsets.length; i++) out[i] = controls[rfOffsets[i]];
        return out;
    }

    protected static double sumOfSquares(double[] values) {
        double sum = 0;
        for (var v : values) sum += v * v;
        return sum;
    }

    /**
     * Populate {@code emfRe}/{@code emfIm} from the step's coupling-integral
     * change. Differentiating {@code Σ (Ex·M_perp − jEy·M_perp) dV} produces
     * the instantaneous EMF that stamps into the coil branches of the MNA.
     */
    protected static void computeEmfs(ProblemGeometry geometry, double[] mx, double[] my,
                                      double dt, double[] sRePrev, double[] sImPrev,
                                      double[] emfRe, double[] emfIm) {
        int nCoils = geometry.circuit().coils().size();
        int nPoints = geometry.pointCount();
        for (int c = 0; c < nCoils; c++) {
            double re = 0, im = 0;
            double[] ex = geometry.coilExFlat()[c];
            double[] ey = geometry.coilEyFlat()[c];
            for (int p = 0; p < nPoints; p++) {
                re += ex[p] * mx[p] + ey[p] * my[p];
                im += ex[p] * my[p] - ey[p] * mx[p];
            }
            emfRe[c] = -(re - sRePrev[c]) / dt;
            emfIm[c] = -(im - sImPrev[c]) / dt;
            sRePrev[c] = re;
            sImPrev[c] = im;
        }
    }

    /**
     * Complex signal observed by the probe in the lab frame. The MNA solves
     * in a rotating frame at {@code ω_sim}, so we multiply the probe's node
     * voltage by {@code exp(+j·ω_sim·t)} before applying the probe's demod
     * phase and gain. Matches {@link ax.xz.mri.service.simulation.SignalTraceComputer}.
     */
    protected static double[] probeSignal(ProblemGeometry geometry, int probeIndex,
                                          CircuitStepEvaluator evaluator,
                                          double tSeconds) {
        if (probeIndex < 0) return new double[]{0, 0};
        var probe = geometry.circuit().probes().get(probeIndex);
        double simR = evaluator.probeVoltageReal(probeIndex);
        double simI = evaluator.probeVoltageImag(probeIndex);
        double labPhase = geometry.omegaSim() * tSeconds;
        double cLab = Math.cos(labPhase), sLab = Math.sin(labPhase);
        double labR = simR * cLab - simI * sLab;
        double labI = simR * sLab + simI * cLab;
        double rad = probe.demodPhaseDeg() * Math.PI / 180.0;
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        double phasedR = (labR * c - labI * s) * probe.gain();
        double phasedI = (labR * s + labI * c) * probe.gain();
        return new double[]{phasedR, phasedI};
    }

    /**
     * Normaliser for the RF-power objective term — one unit per RF
     * envelope channel, bottom-capped at 1 so circuits without a modulator
     * don't divide by zero.
     */
    protected static double referenceRfPower(ax.xz.mri.service.circuit.CompiledCircuit circuit) {
        return Math.max(circuit.rfEnvelopeChannelOffsets().length, 1);
    }

    /**
     * Normalising reference for the squared signal: the upper bound of
     * {@code |s|²} if every grid point were coherently aligned with the
     * primary probe's sensitivity phasor.
     */
    protected static double probeReferenceSquared(ProblemGeometry geometry) {
        int probeIdx = geometry.primaryProbeIndex();
        if (probeIdx < 0) return 1.0;
        // For the reference, pretend every observe link is live and sum magnitudes.
        int nCoils = geometry.circuit().coils().size();
        double magSum = 0;
        for (int c = 0; c < nCoils; c++) {
            for (int p = 0; p < geometry.pointCount(); p++) {
                magSum += Math.hypot(geometry.coilExFlat()[c][p], geometry.coilEyFlat()[c][p]);
            }
        }
        double scaled = geometry.circuit().probes().get(probeIdx).gain() * magSum;
        return scaled * scaled;
    }

    protected static double primaryCoilSensitivityMax(ProblemGeometry geometry) {
        int probeIdx = geometry.primaryProbeIndex();
        if (probeIdx < 0) return 1.0;
        int nCoils = geometry.circuit().coils().size();
        double mag = 0;
        for (int c = 0; c < nCoils; c++) {
            for (int p = 0; p < geometry.pointCount(); p++) {
                mag += Math.hypot(geometry.coilExFlat()[c][p], geometry.coilEyFlat()[c][p]);
            }
        }
        return geometry.circuit().probes().get(probeIdx).gain() * mag;
    }

    protected static double weightedSum(double[] weights, double[] values) {
        double sum = 0;
        for (int i = 0; i < weights.length; i++) sum += weights[i] * values[i];
        return sum;
    }

    protected static double weightedPower(double[] weights, double[] mx, double[] my) {
        double sum = 0;
        for (int i = 0; i < weights.length; i++) sum += weights[i] * (mx[i] * mx[i] + my[i] * my[i]);
        return sum;
    }

    protected static double sq(double v) { return v * v; }
}
