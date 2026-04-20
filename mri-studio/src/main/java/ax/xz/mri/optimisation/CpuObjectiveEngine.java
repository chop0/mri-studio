package ax.xz.mri.optimisation;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.optimisation.ProblemGeometry.DynamicFieldSamples;

import java.util.ArrayList;
import java.util.List;

/** Pure Java CPU objective engine. */
public class CpuObjectiveEngine extends BlochObjectiveEngine {
    private final double finiteDifferenceFactor;

    public CpuObjectiveEngine() {
        this(DEFAULT_EPSILON_FACTOR);
    }

    public CpuObjectiveEngine(double finiteDifferenceFactor) {
        this.finiteDifferenceFactor = finiteDifferenceFactor;
    }

    @Override
    public double[] gradient(OptimisationProblem problem, List<PulseSegment> segments) {
        double[] base = PulseParameterCodec.flatten(segments);
        double[] gradient = new double[base.length];
        double epsilon = Math.max(finiteDifferenceFactor, 1e-12);
        for (int index = 0; index < base.length; index++) {
            double scale = Math.max(Math.abs(base[index]), 1.0);
            double h = scale * epsilon;
            double original = base[index];
            base[index] = original + h;
            double upper = evaluateInternal(problem, PulseParameterCodec.split(base, problem.sequenceTemplate()), false).value();
            base[index] = original - h;
            double lower = evaluateInternal(problem, PulseParameterCodec.split(base, problem.sequenceTemplate()), false).value();
            base[index] = original;
            gradient[index] = (upper - lower) / (2.0 * h);
        }
        return gradient;
    }

    @Override
    protected ObjectiveEvaluation evaluateInternal(
        OptimisationProblem problem,
        List<PulseSegment> segments,
        boolean captureSignal
    ) {
        return super.evaluateInternal(problem, segments, captureSignal);
    }

    /**
     * Build a {@link ProblemGeometry} from the runtime {@link FieldMap},
     * flattening the 2D {@code [r][z]} spatial arrays to one point per
     * {@code (ri, zi)} sample. Per-dynamic-field eigenfield samples are
     * flattened in the same order.
     */
    public static ProblemGeometry geometryFromFieldMap(FieldMap field, int radialStride, int axialStride) {
        int rs = Math.max(1, radialStride);
        int zs = Math.max(1, axialStride);
        int nr = (field.rMm.length + rs - 1) / rs;
        int nz = (field.zMm.length + zs - 1) / zs;
        int count = nr * nz;

        double[] mx0 = new double[count];
        double[] my0 = new double[count];
        double[] mz0 = new double[count];
        double[] staticBz = new double[count];
        double[] wIn = new double[count];
        double[] wOut = new double[count];

        int dynamicCount = field.dynamicFields == null ? 0 : field.dynamicFields.size();
        double[][] exFlat = new double[dynamicCount][count];
        double[][] eyFlat = new double[dynamicCount][count];
        double[][] ezFlat = new double[dynamicCount][count];

        double sliceHalf = field.sliceHalf == null ? 0.005 : field.sliceHalf;
        int offset = 0;
        for (int ri = 0; ri < field.rMm.length; ri += rs) {
            for (int zi = 0; zi < field.zMm.length; zi += zs) {
                double zM = field.zMm[zi] * 1e-3;
                mx0[offset] = field.mx0 == null ? 0.0 : field.mx0[ri][zi];
                my0[offset] = field.my0 == null ? 0.0 : field.my0[ri][zi];
                mz0[offset] = field.mz0 == null ? 1.0 : field.mz0[ri][zi];
                staticBz[offset] = field.staticBz == null ? 0.0 : field.staticBz[ri][zi];
                for (int d = 0; d < dynamicCount; d++) {
                    var df = field.dynamicFields.get(d);
                    exFlat[d][offset] = df.ex[ri][zi];
                    eyFlat[d][offset] = df.ey[ri][zi];
                    ezFlat[d][offset] = df.ez[ri][zi];
                }
                double geomIn = Math.abs(zM) < sliceHalf ? 1.0 : 0.0;
                double mp0 = Math.hypot(mx0[offset], my0[offset]);
                wIn[offset] = field.mx0 == null ? geomIn : (geomIn > 0 && mp0 > 0.3 ? 1.0 : 0.0);
                offset++;
            }
        }
        double outSum = 0.0;
        offset = 0;
        for (int ri = 0; ri < field.rMm.length; ri += rs) {
            for (int zi = 0; zi < field.zMm.length; zi += zs) {
                double zM = field.zMm[zi] * 1e-3;
                double dist = Math.max(Math.abs(zM) - sliceHalf, 0.0) / Math.max(sliceHalf, 1e-9);
                wOut[offset] = dist > 0.0 ? 1.0 + dist * dist : 0.0;
                outSum += wOut[offset];
                offset++;
            }
        }
        if (outSum > 1e-9) {
            double countOut = 0.0;
            for (double value : wOut) if (value > 0.0) countOut++;
            double mean = outSum / Math.max(countOut, 1.0);
            for (int index = 0; index < wOut.length; index++) {
                if (wOut[index] > 0.0) wOut[index] /= mean;
            }
        }
        double sMax = 0.0;
        for (double value : wIn) sMax += value;

        var dynamics = new ArrayList<DynamicFieldSamples>(dynamicCount);
        for (int d = 0; d < dynamicCount; d++) {
            var df = field.dynamicFields.get(d);
            dynamics.add(new DynamicFieldSamples(
                df.name, df.channelOffset, df.channelCount, df.deltaOmega,
                exFlat[d], eyFlat[d], ezFlat[d]));
        }

        return new ProblemGeometry(mx0, my0, mz0, staticBz, wIn, wOut, List.copyOf(dynamics),
            sMax, field.gamma, field.t1, field.t2, nr, nz);
    }
}
