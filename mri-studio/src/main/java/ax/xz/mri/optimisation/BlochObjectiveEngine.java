package ax.xz.mri.optimisation;

import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;

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
        double omegaSim = geometry.gamma() * 0; // reference B0 is baked into staticBz; omegaSim not reconstructable here, caller may pass 0
        double timeMicros = 0.0;
        double tSeconds = 0.0;

        var signalPoints = captureSignal ? new ArrayList<SignalTrace.Point>() : null;
        if (captureSignal) {
            var initial = probeSignal(geometry, primaryIdx, mx, my, evaluator);
            signalPoints.add(new SignalTrace.Point(0.0, initial[0], initial[1]));
        }

        double jIn = 0.0;
        double jOut = 0.0;
        double powerOut = 0.0;
        double rfPower = 0.0;
        double rfSmooth = 0.0;
        double gateSwitch = 0.0;
        double gateBinary = 0.0;
        double totalDt = 0.0;
        int quadChannels = countQuadratureChannels(circuit);

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
                evaluator.evaluate(step.controls(), tSeconds, omegaSim);
                applyStep(geometry, evaluator, dt, mx, my, mz);
                double sigGate = 1.0 - clamp(step.rfGate(), 0.0, 1.0);
                double[] signal = probeSignal(geometry, primaryIdx, mx, my, evaluator);
                double sigMag2 = signal[0] * signal[0] + signal[1] * signal[1];
                double sxOut = weightedSum(geometry.wOut(), mx);
                double syOut = weightedSum(geometry.wOut(), my);
                double powerOutStep = weightedPower(geometry.wOut(), mx, my);
                jIn += sigGate * sigMag2;
                jOut += sigGate * (sxOut * sxOut + syOut * syOut);
                powerOut += sigGate * powerOutStep;
                double[] quadNow = quadratureComponents(circuit, step, quadChannels);
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

    protected static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /**
     * Apply one Bloch step to every point using the pre-evaluated per-coil
     * drives from {@code evaluator}.
     */
    protected static void applyStep(ProblemGeometry geometry, CircuitStepEvaluator evaluator, double dt,
                                    double[] mx, double[] my, double[] mz) {
        double e2 = Math.exp(-dt / geometry.t2());
        double e1 = Math.exp(-dt / geometry.t1());
        int nCoils = geometry.circuit().coils().size();
        int nPoints = geometry.pointCount();
        double[][] ex = geometry.coilExFlat();
        double[][] ey = geometry.coilEyFlat();
        double[][] ez = geometry.coilEzFlat();

        double[] iDrive = new double[nCoils];
        double[] qDrive = new double[nCoils];
        for (int c = 0; c < nCoils; c++) {
            iDrive[c] = evaluator.coilDriveI(c);
            qDrive[c] = evaluator.coilDriveQ(c);
        }

        for (int p = 0; p < nPoints; p++) {
            double bx = 0, by = 0, bz = geometry.staticBz()[p];
            for (int c = 0; c < nCoils; c++) {
                double I = iDrive[c];
                double Q = qDrive[c];
                double exp = ex[c][p], eyp = ey[c][p], ezp = ez[c][p];
                bx += I * exp - Q * eyp;
                by += I * eyp + Q * exp;
                bz += I * ezp;
            }
            double bPerp2 = bx * bx + by * by;
            if (bPerp2 < 1e-30) {
                double th = geometry.gamma() * bz * dt;
                double c = Math.cos(th), s = Math.sin(th);
                double nmx = (mx[p] * c - my[p] * s) * e2;
                double nmy = (mx[p] * s + my[p] * c) * e2;
                mx[p] = nmx;
                my[p] = nmy;
                mz[p] = 1.0 + (mz[p] - 1.0) * e1;
            } else {
                double bm = Math.sqrt(bx * bx + by * by + bz * bz + HardwareLimits.EPSILON);
                double nx = bx / bm, ny = by / bm, nz = bz / bm;
                double th = geometry.gamma() * bm * dt;
                double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
                double nd = nx * mx[p] + ny * my[p] + nz * mz[p];
                double cx = ny * mz[p] - nz * my[p];
                double cy = nz * mx[p] - nx * mz[p];
                double cz = nx * my[p] - ny * mx[p];
                double nmx = (mx[p] * c + cx * s + nx * nd * omc) * e2;
                double nmy = (my[p] * c + cy * s + ny * nd * omc) * e2;
                double nmz = 1.0 + (mz[p] * c + cz * s + nz * nd * omc - 1.0) * e1;
                mx[p] = nmx;
                my[p] = nmy;
                mz[p] = nmz;
            }
        }
    }

    protected static int countQuadratureChannels(ax.xz.mri.service.circuit.CompiledCircuit circuit) {
        int count = 0;
        for (var src : circuit.sources()) if (src.kind() == AmplitudeKind.QUADRATURE) count += 2;
        return count;
    }

    protected static double[] quadratureComponents(ax.xz.mri.service.circuit.CompiledCircuit circuit, PulseStep step, int quadChannels) {
        double[] out = new double[quadChannels];
        int k = 0;
        var controls = step.controls();
        for (var src : circuit.sources()) {
            if (src.kind() != AmplitudeKind.QUADRATURE) continue;
            out[k++] = controls[src.channelOffset()];
            out[k++] = controls[src.channelOffset() + 1];
        }
        return out;
    }

    protected static double sumOfSquares(double[] values) {
        double sum = 0;
        for (var v : values) sum += v * v;
        return sum;
    }

    /** Complex signal observed by the primary probe, gated by switch states. */
    protected static double[] probeSignal(ProblemGeometry geometry, int probeIndex,
                                          double[] mx, double[] my, CircuitStepEvaluator evaluator) {
        if (probeIndex < 0) return new double[]{0, 0};
        var circuit = geometry.circuit();
        var probe = circuit.probes().get(probeIndex);
        int nCoils = circuit.coils().size();
        int nPoints = geometry.pointCount();
        double[] coilRe = new double[nCoils];
        double[] coilIm = new double[nCoils];
        for (int c = 0; c < nCoils; c++) {
            double re = 0, im = 0;
            for (int p = 0; p < nPoints; p++) {
                double ex = geometry.coilExFlat()[c][p];
                double ey = geometry.coilEyFlat()[c][p];
                re += ex * mx[p] + ey * my[p];
                im += ex * my[p] - ey * mx[p];
            }
            coilRe[c] = re;
            coilIm[c] = im;
        }
        double sr = 0, si = 0;
        for (var link : circuit.observes()) {
            if (link.endpointIndex() != probeIndex) continue;
            if (!evaluator.allClosed(link.switchIndices())) continue;
            double sign = link.forwardPolarity() ? 1.0 : -1.0;
            sr += sign * coilRe[link.coilIndex()];
            si += sign * coilIm[link.coilIndex()];
        }
        double rad = probe.demodPhaseDeg() * Math.PI / 180.0;
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        double phasedR = (sr * c - si * s) * probe.gain();
        double phasedI = (sr * s + si * c) * probe.gain();
        return new double[]{phasedR, phasedI};
    }

    protected static double referenceRfPower(ax.xz.mri.service.circuit.CompiledCircuit circuit) {
        double sum = 0;
        boolean any = false;
        for (var src : circuit.sources()) {
            if (src.kind() != AmplitudeKind.QUADRATURE) continue;
            any = true;
            sum += 1.0;
        }
        return any ? sum : 1.0;
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
