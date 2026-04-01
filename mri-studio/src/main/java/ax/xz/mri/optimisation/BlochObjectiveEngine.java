package ax.xz.mri.optimisation;

import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.SignalTrace;

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

    protected ObjectiveEvaluation evaluateInternal(
        OptimisationProblem problem,
        List<PulseSegment> segments,
        boolean captureSignal
    ) {
        validateSegments(problem.sequenceTemplate(), segments);
        return switch (problem.objectiveSpec().mode()) {
            case FULL_TRAIN -> evaluateFullTrain(problem, segments, captureSignal);
            case PERIODIC_CYCLE -> evaluatePeriodicCycle(problem, segments, captureSignal);
        };
    }

    private ObjectiveEvaluation evaluateFullTrain(OptimisationProblem problem, List<PulseSegment> segments, boolean captureSignal) {
        var geometry = problem.geometry();
        var objective = problem.objectiveSpec();
        var mx = geometry.mx0().clone();
        var my = geometry.my0().clone();
        var mz = geometry.mz0().clone();
        var timeMicros = 0.0;
        var signalPoints = captureSignal ? new ArrayList<SignalTrace.Point>() : null;
        if (captureSignal) {
            signalPoints.add(new SignalTrace.Point(0.0, coherentSignalMagnitude(geometry.wIn(), mx, my)));
        }

        double jIn = 0.0;
        double jOut = 0.0;
        double powerOut = 0.0;
        double rfPower = 0.0;
        double rfSmooth = 0.0;
        double gateSwitch = 0.0;
        double gateBinary = 0.0;
        double totalDt = 0.0;

        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var spec = problem.sequenceTemplate().segments().get(segmentIndex);
            double[][] previousRf = new double[2][2];
            double previousGate = 0.0;
            boolean haveGate = false;
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                var step = segment.steps().get(stepIndex);
                double dt = spec.dt();
                totalDt += dt;
                applyStep(geometry, step, dt, mx, my, mz, geometry.t1(), geometry.t2());
                double sigGate = 1.0 - clamp(step.rfGate(), 0.0, 1.0);
                double sx = weightedSum(geometry.wIn(), mx);
                double sy = weightedSum(geometry.wIn(), my);
                double sxOut = weightedSum(geometry.wOut(), mx);
                double syOut = weightedSum(geometry.wOut(), my);
                double powerOutStep = weightedPower(geometry.wOut(), mx, my);
                jIn += sigGate * (sx * sx + sy * sy);
                jOut += sigGate * (sxOut * sxOut + syOut * syOut);
                powerOut += sigGate * powerOutStep;
                rfPower += (step.b1x() * step.b1x() + step.b1y() * step.b1y()) * dt;
                if (stepIndex >= 2) {
                    double d2x = step.b1x() - 2.0 * previousRf[0][0] + previousRf[1][0];
                    double d2y = step.b1y() - 2.0 * previousRf[0][1] + previousRf[1][1];
                    rfSmooth += (d2x * d2x + d2y * d2y) * dt;
                }
                if (haveGate) {
                    double dg = step.rfGate() - previousGate;
                    gateSwitch += dg * dg * dt;
                }
                gateBinary += step.rfGate() * (1.0 - step.rfGate()) * dt;
                previousRf[1][0] = previousRf[0][0];
                previousRf[1][1] = previousRf[0][1];
                previousRf[0][0] = step.b1x();
                previousRf[0][1] = step.b1y();
                previousGate = step.rfGate();
                haveGate = true;
                timeMicros += dt * 1e6;
                if (captureSignal) {
                    signalPoints.add(new SignalTrace.Point(timeMicros, sigGate * coherentSignalMagnitude(geometry.wIn(), mx, my)));
                }
            }
        }

        double signalRef = Math.max(geometry.sMax() * geometry.sMax(), 1.0);
        double powerRef = Math.max(geometry.sMax(), 1.0);
        double rfTimeRef = Math.max(totalDt, 1e-12);
        double rfPowerRef = OptimisationHardwareLimits.B1_MAX * OptimisationHardwareLimits.B1_MAX * rfTimeRef;

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
        var geometry = problem.geometry();
        var objective = problem.objectiveSpec();
        var template = problem.sequenceTemplate();
        int prefixSegments = template.prefixSegmentCount();
        int cycleStart = 0;
        for (int index = 0; index < prefixSegments; index++) {
            cycleStart += template.segments().get(index).totalSteps();
        }

        double[][][] cycleMaps = new double[geometry.pointCount()][4][4];
        for (int point = 0; point < geometry.pointCount(); point++) {
            cycleMaps[point] = identity4();
        }
        for (int segmentIndex = prefixSegments; segmentIndex < segments.size(); segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var spec = template.segments().get(segmentIndex);
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                var step = segment.steps().get(stepIndex);
                double dt = spec.dt();
                for (int point = 0; point < geometry.pointCount(); point++) {
                    cycleMaps[point] = multiply(stepMatrix(geometry, point, step, dt), cycleMaps[point]);
                }
            }
        }

        var steadyMx = new double[geometry.pointCount()];
        var steadyMy = new double[geometry.pointCount()];
        var steadyMz = new double[geometry.pointCount()];
        for (int point = 0; point < geometry.pointCount(); point++) {
            double[][] linear = new double[][]{
                {1.0 - cycleMaps[point][0][0], -cycleMaps[point][0][1], -cycleMaps[point][0][2]},
                {-cycleMaps[point][1][0], 1.0 - cycleMaps[point][1][1], -cycleMaps[point][1][2]},
                {-cycleMaps[point][2][0], -cycleMaps[point][2][1], 1.0 - cycleMaps[point][2][2]}
            };
            double[] rhs = new double[]{
                cycleMaps[point][0][3],
                cycleMaps[point][1][3],
                cycleMaps[point][2][3]
            };
            double[] solved = solve3(linear, rhs);
            steadyMx[point] = solved[0];
            steadyMy[point] = solved[1];
            steadyMz[point] = solved[2];
        }

        var prefixMx = geometry.mx0().clone();
        var prefixMy = geometry.my0().clone();
        var prefixMz = geometry.mz0().clone();
        for (int segmentIndex = 0; segmentIndex < prefixSegments; segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var spec = template.segments().get(segmentIndex);
            for (var step : segment.steps()) {
                applyStep(geometry, step, spec.dt(), prefixMx, prefixMy, prefixMz, geometry.t1(), geometry.t2());
            }
        }

        double handoffWeightRef = Math.max(geometry.sMax(), 1.0);
        double handoff = 0.0;
        for (int point = 0; point < geometry.pointCount(); point++) {
            double weight = geometry.wIn()[point] + 0.25 * geometry.wOut()[point];
            handoff += weight * sq(prefixMx[point] - steadyMx[point])
                + weight * sq(prefixMy[point] - steadyMy[point])
                + weight * sq(prefixMz[point] - steadyMz[point]);
        }
        handoff /= handoffWeightRef;

        var cycleMx = steadyMx.clone();
        var cycleMy = steadyMy.clone();
        var cycleMz = steadyMz.clone();
        var signalPoints = captureSignal ? new ArrayList<SignalTrace.Point>() : null;
        double timeMicros = 0.0;
        if (captureSignal) {
            signalPoints.add(new SignalTrace.Point(0.0, coherentSignalMagnitude(geometry.wIn(), geometry.mx0(), geometry.my0())));
        }
        double jIn = 0.0;
        double jOut = 0.0;
        double powerOut = 0.0;
        double rfPower = 0.0;
        double rfSmooth = 0.0;
        double gateSwitch = 0.0;
        double gateBinary = 0.0;
        double totalDt = 0.0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var spec = template.segments().get(segmentIndex);
            double[][] previousRf = new double[2][2];
            double previousGate = 0.0;
            boolean haveGate = false;
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                var step = segment.steps().get(stepIndex);
                double dt = spec.dt();
                totalDt += dt;
                if (segmentIndex >= prefixSegments) {
                    applyStep(geometry, step, dt, cycleMx, cycleMy, cycleMz, geometry.t1(), geometry.t2());
                    double sigGate = 1.0 - clamp(step.rfGate(), 0.0, 1.0);
                    double sx = weightedSum(geometry.wIn(), cycleMx);
                    double sy = weightedSum(geometry.wIn(), cycleMy);
                    double sxOut = weightedSum(geometry.wOut(), cycleMx);
                    double syOut = weightedSum(geometry.wOut(), cycleMy);
                    double powerOutStep = weightedPower(geometry.wOut(), cycleMx, cycleMy);
                    jIn += sigGate * (sx * sx + sy * sy);
                    jOut += sigGate * (sxOut * sxOut + syOut * syOut);
                    powerOut += sigGate * powerOutStep;
                    timeMicros += dt * 1e6;
                    if (captureSignal) {
                        signalPoints.add(new SignalTrace.Point(timeMicros, sigGate * coherentSignalMagnitude(geometry.wIn(), cycleMx, cycleMy)));
                    }
                }
                rfPower += (step.b1x() * step.b1x() + step.b1y() * step.b1y()) * dt;
                if (stepIndex >= 2) {
                    double d2x = step.b1x() - 2.0 * previousRf[0][0] + previousRf[1][0];
                    double d2y = step.b1y() - 2.0 * previousRf[0][1] + previousRf[1][1];
                    rfSmooth += (d2x * d2x + d2y * d2y) * dt;
                }
                if (haveGate) {
                    double dg = step.rfGate() - previousGate;
                    gateSwitch += dg * dg * dt;
                }
                gateBinary += step.rfGate() * (1.0 - step.rfGate()) * dt;
                previousRf[1][0] = previousRf[0][0];
                previousRf[1][1] = previousRf[0][1];
                previousRf[0][0] = step.b1x();
                previousRf[0][1] = step.b1y();
                previousGate = step.rfGate();
                haveGate = true;
            }
        }

        double signalRef = Math.max(geometry.sMax() * geometry.sMax(), 1.0);
        double powerRef = Math.max(geometry.sMax(), 1.0);
        double rfTimeRef = Math.max(totalDt, 1e-12);
        double rfPowerRef = OptimisationHardwareLimits.B1_MAX * OptimisationHardwareLimits.B1_MAX * rfTimeRef;
        double value = -(
            jIn / signalRef
                - objective.lamOut() * (jOut / signalRef)
                - objective.lamPow() * (powerOut / powerRef)
                - objective.rfPenalty() * (rfPower / rfPowerRef)
                - objective.rfSmoothPenalty() * (rfSmooth / rfPowerRef)
                - objective.gateSwitchPenalty() * (gateSwitch / rfTimeRef)
                - objective.gateBinaryPenalty() * (gateBinary / rfTimeRef)
                - objective.handoffPenalty() * handoff
        );
        return new ObjectiveEvaluation(value, captureSignal ? new SignalTrace(List.copyOf(signalPoints)) : null);
    }

    protected static void validateSegments(SequenceTemplate template, List<PulseSegment> segments) {
        if (segments.size() != template.segments().size()) {
            throw new IllegalArgumentException("segment count does not match sequence template");
        }
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).steps().size() != template.segments().get(index).totalSteps()) {
                throw new IllegalArgumentException("step count does not match sequence template at segment " + index);
            }
        }
    }

    protected static double gradientScaleForControlIndex(int controlIndex) {
        return switch (controlIndex % 5) {
            case 0, 1 -> OptimisationHardwareLimits.B1_MAX;
            case 2 -> OptimisationHardwareLimits.GX_MAX;
            case 3 -> OptimisationHardwareLimits.GZ_MAX;
            default -> 1.0;
        };
    }

    protected static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    protected static void applyStep(
        ProblemGeometry geometry,
        PulseStep step,
        double dt,
        double[] mx,
        double[] my,
        double[] mz,
        double t1,
        double t2
    ) {
        double e2 = Math.exp(-dt / t2);
        double e1 = Math.exp(-dt / t1);
        boolean rfOn = step.isRfOn();
        for (int point = 0; point < geometry.pointCount(); point++) {
            if (!rfOn) {
                double omega = geometry.gamma() * (
                    geometry.dBz()[point]
                        + step.gx() * geometry.gxm()[point]
                        + step.gz() * geometry.gzm()[point]
                );
                double th = omega * dt;
                double c = Math.cos(th);
                double s = Math.sin(th);
                double nmx = (mx[point] * c - my[point] * s) * e2;
                double nmy = (mx[point] * s + my[point] * c) * e2;
                mx[point] = nmx;
                my[point] = nmy;
                mz[point] = 1.0 + (mz[point] - 1.0) * e1;
            } else {
                double bx = step.b1x() * geometry.b1s()[point];
                double by = step.b1y() * geometry.b1s()[point];
                double bz = geometry.dBz()[point]
                    + step.gx() * geometry.gxm()[point]
                    + step.gz() * geometry.gzm()[point];
                double bm = Math.sqrt(bx * bx + by * by + bz * bz + HardwareLimits.EPSILON);
                double nx = bx / bm;
                double ny = by / bm;
                double nz = bz / bm;
                double th = geometry.gamma() * bm * dt;
                double c = Math.cos(th);
                double s = Math.sin(th);
                double omc = 1.0 - c;
                double nd = nx * mx[point] + ny * my[point] + nz * mz[point];
                double cx = ny * mz[point] - nz * my[point];
                double cy = nz * mx[point] - nx * mz[point];
                double cz = nx * my[point] - ny * mx[point];
                double nmx = (mx[point] * c + cx * s + nx * nd * omc) * e2;
                double nmy = (my[point] * c + cy * s + ny * nd * omc) * e2;
                double nmz = 1.0 + (mz[point] * c + cz * s + nz * nd * omc - 1.0) * e1;
                mx[point] = nmx;
                my[point] = nmy;
                mz[point] = nmz;
            }
        }
    }

    protected static double[][] stepMatrix(ProblemGeometry geometry, int point, PulseStep step, double dt) {
        double e2 = Math.exp(-dt / geometry.t2());
        double e1 = Math.exp(-dt / geometry.t1());
        if (!step.isRfOn()) {
            double omega = geometry.gamma() * (
                geometry.dBz()[point]
                    + step.gx() * geometry.gxm()[point]
                    + step.gz() * geometry.gzm()[point]
            );
            double th = omega * dt;
            double c = Math.cos(th);
            double s = Math.sin(th);
            return new double[][]{
                {e2 * c, -e2 * s, 0.0, 0.0},
                {e2 * s, e2 * c, 0.0, 0.0},
                {0.0, 0.0, e1, 1.0 - e1},
                {0.0, 0.0, 0.0, 1.0}
            };
        }
        double bx = step.b1x() * geometry.b1s()[point];
        double by = step.b1y() * geometry.b1s()[point];
        double bz = geometry.dBz()[point]
            + step.gx() * geometry.gxm()[point]
            + step.gz() * geometry.gzm()[point];
        double bm = Math.sqrt(bx * bx + by * by + bz * bz + HardwareLimits.EPSILON);
        double nx = bx / bm;
        double ny = by / bm;
        double nz = bz / bm;
        double th = geometry.gamma() * bm * dt;
        double c = Math.cos(th);
        double s = Math.sin(th);
        double omc = 1.0 - c;
        double r00 = c + nx * nx * omc;
        double r01 = nx * ny * omc - nz * s;
        double r02 = nx * nz * omc + ny * s;
        double r10 = ny * nx * omc + nz * s;
        double r11 = c + ny * ny * omc;
        double r12 = ny * nz * omc - nx * s;
        double r20 = nz * nx * omc - ny * s;
        double r21 = nz * ny * omc + nx * s;
        double r22 = c + nz * nz * omc;
        return new double[][]{
            {e2 * r00, e2 * r01, e2 * r02, 0.0},
            {e2 * r10, e2 * r11, e2 * r12, 0.0},
            {e1 * r20, e1 * r21, e1 * r22, 1.0 - e1},
            {0.0, 0.0, 0.0, 1.0}
        };
    }

    protected static double[][] multiply(double[][] a, double[][] b) {
        double[][] result = new double[4][4];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double sum = 0.0;
                for (int k = 0; k < 4; k++) {
                    sum += a[row][k] * b[k][col];
                }
                result[row][col] = sum;
            }
        }
        return result;
    }

    protected static double[] solve3(double[][] matrix, double[] rhs) {
        double[][] a = new double[3][4];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(matrix[row], 0, a[row], 0, 3);
            a[row][3] = rhs[row];
        }
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 3; row++) {
                if (Math.abs(a[row][pivot]) > Math.abs(a[best][pivot])) best = row;
            }
            if (best != pivot) {
                double[] tmp = a[pivot];
                a[pivot] = a[best];
                a[best] = tmp;
            }
            double divisor = Math.abs(a[pivot][pivot]) < 1e-9 ? (a[pivot][pivot] >= 0 ? 1e-9 : -1e-9) : a[pivot][pivot];
            for (int col = pivot; col < 4; col++) {
                a[pivot][col] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == pivot) continue;
                double factor = a[row][pivot];
                for (int col = pivot; col < 4; col++) {
                    a[row][col] -= factor * a[pivot][col];
                }
            }
        }
        return new double[]{a[0][3], a[1][3], a[2][3]};
    }

    protected static double[][] identity4() {
        return new double[][]{
            {1.0, 0.0, 0.0, 0.0},
            {0.0, 1.0, 0.0, 0.0},
            {0.0, 0.0, 1.0, 0.0},
            {0.0, 0.0, 0.0, 1.0}
        };
    }

    protected static double weightedSum(double[] weights, double[] values) {
        double sum = 0.0;
        for (int index = 0; index < weights.length; index++) {
            sum += weights[index] * values[index];
        }
        return sum;
    }

    protected static double weightedPower(double[] weights, double[] mx, double[] my) {
        double sum = 0.0;
        for (int index = 0; index < weights.length; index++) {
            sum += weights[index] * (mx[index] * mx[index] + my[index] * my[index]);
        }
        return sum;
    }

    protected static double coherentSignalMagnitude(double[] weights, double[] mx, double[] my) {
        double sx = weightedSum(weights, mx);
        double sy = weightedSum(weights, my);
        return Math.hypot(sx, sy);
    }

    protected static double sq(double value) {
        return value * value;
    }
}
