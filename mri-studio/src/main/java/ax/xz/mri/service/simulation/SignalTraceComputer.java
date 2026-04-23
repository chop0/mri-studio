package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integrates Bloch dynamics across the (r, z) grid and emits one complex
 * {@link SignalTrace} per {@link ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe
 * Probe} in the circuit.
 *
 * <p>Each step:
 * <ol>
 *   <li>{@link CircuitStepEvaluator} resolves this step's switch closures and
 *       per-coil complex drive amplitudes (I, Q).</li>
 *   <li>At every grid point the total rotating-frame B is assembled as
 *       {@code staticBz · ẑ + Σ_c (I_c·E_c − jQ_c·E_c)} (each coil's
 *       eigenfield being its unit-current B-field pattern), then a Rodrigues
 *       rotation + T1/T2 decay advances M by one dt.</li>
 *   <li>For each observe link {@code (probe, coil, switches[])} the live
 *       probe value is the reciprocity integral
 *       {@code Σ_p (Ex − jEy)·(Mx + jMy)}, summed across every coil observed
 *       through the probe, gated by the switch states on the path.</li>
 * </ol>
 */
public final class SignalTraceComputer {
    private SignalTraceComputer() {}

    public static SignalTrace compute(BlochData data, List<PulseSegment> pulse) {
        return computeAll(data, pulse).primary();
    }

    public static MultiProbeSignalTrace computeAll(BlochData data, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return MultiProbeSignalTrace.empty();
        var f = data.field();
        var circuit = f.circuit;
        if (circuit == null || circuit.probes().isEmpty()) return MultiProbeSignalTrace.empty();

        var pts = new ArrayList<FieldPoint>();
        for (int ir = 0; ir < f.rMm.length; ir++) {
            for (int iz = 0; iz < f.zMm.length; iz++) {
                pts.add(FieldInterpolator.interpolate(f, f.rMm[ir], f.zMm[iz]));
            }
        }
        int np = pts.size();
        if (np == 0) return MultiProbeSignalTrace.empty();

        var mx = new double[np];
        var my = new double[np];
        var mz = new double[np];
        for (int p = 0; p < np; p++) {
            var fp = pts.get(p);
            mx[p] = fp.mx0();
            my[p] = fp.my0();
            mz[p] = fp.mz0();
        }

        int nProbes = circuit.probes().size();
        var traces = new ArrayList<List<Point>>(nProbes);
        double[] cosPhase = new double[nProbes];
        double[] sinPhase = new double[nProbes];
        for (int k = 0; k < nProbes; k++) {
            traces.add(new ArrayList<>());
            traces.get(k).add(new Point(0, 0, 0));
            double rad = circuit.probes().get(k).demodPhaseDeg() * Math.PI / 180.0;
            cosPhase[k] = Math.cos(rad);
            sinPhase[k] = Math.sin(rad);
        }

        int nCoils = circuit.coils().size();
        var evaluator = new CircuitStepEvaluator(circuit);
        double t = 0;
        double omegaSim = f.gamma * f.b0Ref;

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double e2 = Math.exp(-seg.dt() / f.t2);
            double e1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                evaluator.evaluate(step.controls(), t, omegaSim);

                for (int p = 0; p < np; p++) {
                    var fp = pts.get(p);
                    double bx = 0, by = 0;
                    double bz = fp.staticBz();
                    for (int c = 0; c < nCoils; c++) {
                        double I = evaluator.coilDriveI(c);
                        double Q = evaluator.coilDriveQ(c);
                        double ex = fp.coilEx()[c], ey = fp.coilEy()[c], ez = fp.coilEz()[c];
                        bx += I * ex - Q * ey;
                        by += I * ey + Q * ex;
                        bz += I * ez;
                    }

                    double bPerp2 = bx * bx + by * by;
                    if (bPerp2 < 1e-30) {
                        double om = f.gamma * bz;
                        double th = om * seg.dt();
                        double c = Math.cos(th), s = Math.sin(th);
                        double nmx = (mx[p] * c - my[p] * s) * e2;
                        double nmy = (mx[p] * s + my[p] * c) * e2;
                        mx[p] = nmx;
                        my[p] = nmy;
                        mz[p] = 1 + (mz[p] - 1) * e1;
                    } else {
                        double bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
                        double th = f.gamma * bm * seg.dt();
                        double nx = bx / bm, ny = by / bm, nz = bz / bm;
                        double c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
                        double nd = nx * mx[p] + ny * my[p] + nz * mz[p];
                        double cx = ny * mz[p] - nz * my[p];
                        double cy = nz * mx[p] - nx * mz[p];
                        double cz = nx * my[p] - ny * mx[p];
                        mx[p] = (mx[p] * c + cx * s + nx * nd * oc) * e2;
                        my[p] = (my[p] * c + cy * s + ny * nd * oc) * e2;
                        mz[p] = 1 + (mz[p] * c + cz * s + nz * nd * oc - 1) * e1;
                    }
                }

                t += seg.dt();
                double tUs = Math.round(t * 1e7) / 10.0;

                var coilRe = new double[nCoils];
                var coilIm = new double[nCoils];
                for (int c = 0; c < nCoils; c++) {
                    double re = 0, im = 0;
                    for (int p = 0; p < np; p++) {
                        var fp = pts.get(p);
                        re += fp.coilEx()[c] * mx[p] + fp.coilEy()[c] * my[p];
                        im += fp.coilEx()[c] * my[p] - fp.coilEy()[c] * mx[p];
                    }
                    coilRe[c] = re;
                    coilIm[c] = im;
                }

                for (int pk = 0; pk < nProbes; pk++) {
                    double sr = 0, si2 = 0;
                    for (var link : circuit.observes()) {
                        if (link.endpointIndex() != pk) continue;
                        if (!evaluator.allClosed(link.switchIndices())) continue;
                        double sign = link.forwardPolarity() ? 1.0 : -1.0;
                        sr += sign * coilRe[link.coilIndex()];
                        si2 += sign * coilIm[link.coilIndex()];
                    }
                    var probe = circuit.probes().get(pk);
                    double phasedR = (sr * cosPhase[pk] - si2 * sinPhase[pk]) * probe.gain();
                    double phasedI = (sr * sinPhase[pk] + si2 * cosPhase[pk]) * probe.gain();
                    traces.get(pk).add(new Point(tUs, phasedR, phasedI));
                }
            }
        }

        var byProbe = new LinkedHashMap<String, SignalTrace>();
        for (int k = 0; k < nProbes; k++) {
            byProbe.put(circuit.probes().get(k).name(), new SignalTrace(List.copyOf(traces.get(k))));
        }
        return new MultiProbeSignalTrace(Map.copyOf(byProbe), circuit.probes().get(0).name());
    }
}
