package ax.xz.mri.optimisation;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.sequence.PulseSegment;

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

    /**
     * Build a {@link ProblemGeometry} from a runtime {@link FieldMap},
     * flattening the {@code [r][z]} spatial arrays to one point per
     * {@code (ri, zi)} sample. Per-coil eigenfield samples are flattened
     * alongside in the same order as the field map's compiled coils.
     */
    public static ProblemGeometry geometryFromFieldMap(FieldMap field, int radialStride, int axialStride) {
        if (field.circuit == null) {
            throw new IllegalStateException("FieldMap has no compiled circuit; cannot build ProblemGeometry");
        }
        int rs = Math.max(1, radialStride);
        int zs = Math.max(1, axialStride);
        int nr = (field.rMm.length + rs - 1) / rs;
        int nz = (field.zMm.length + zs - 1) / zs;
        int count = nr * nz;

        double[] mx0 = new double[count];
        double[] my0 = new double[count];
        double[] mz0 = new double[count];
        double[] staticBz = new double[count];
        double[] wOut = new double[count];
        int nCoils = field.circuit.coils().size();
        double[][] exFlat = new double[nCoils][count];
        double[][] eyFlat = new double[nCoils][count];
        double[][] ezFlat = new double[nCoils][count];
        double sliceHalf = field.sliceHalf == null ? 0.005 : field.sliceHalf;

        int offset = 0;
        for (int ri = 0; ri < field.rMm.length; ri += rs) {
            for (int zi = 0; zi < field.zMm.length; zi += zs) {
                mx0[offset] = field.mx0 == null ? 0.0 : field.mx0[ri][zi];
                my0[offset] = field.my0 == null ? 0.0 : field.my0[ri][zi];
                mz0[offset] = field.mz0 == null ? 1.0 : field.mz0[ri][zi];
                staticBz[offset] = field.staticBz == null ? 0.0 : field.staticBz[ri][zi];
                for (int c = 0; c < nCoils; c++) {
                    var coil = field.circuit.coils().get(c);
                    exFlat[c][offset] = coil.ex()[ri][zi];
                    eyFlat[c][offset] = coil.ey()[ri][zi];
                    ezFlat[c][offset] = coil.ez()[ri][zi];
                }
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
            for (double v : wOut) if (v > 0.0) countOut++;
            double mean = outSum / Math.max(countOut, 1.0);
            for (int i = 0; i < wOut.length; i++) if (wOut[i] > 0.0) wOut[i] /= mean;
        }

        return new ProblemGeometry(mx0, my0, mz0, staticBz, wOut,
            exFlat, eyFlat, ezFlat, field.circuit,
            field.gamma, field.b0Ref, field.t1, field.t2, nr, nz);
    }
}
