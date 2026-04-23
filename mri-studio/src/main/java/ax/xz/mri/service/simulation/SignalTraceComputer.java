package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.field.ReceiveCoilMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiCoilSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integrates the Bloch equations across the (r, z) grid and emits, for each
 * configured receive coil, the complex demodulated signal
 * <pre>
 *   s(t) = gain · e^(i·phaseDeg·π/180) · Σ (Eₓ(r) − i·E_y(r)) · (Mₓ(r,t) + i·M_y(r,t)) dV
 * </pre>
 *
 * <p>The legacy single-trace {@link #compute(BlochData, List)} returns the
 * primary coil's trace (the first entry in
 * {@link ax.xz.mri.model.simulation.SimulationConfig#receiveCoils()}). Callers
 * that want every coil use {@link #computeAll(BlochData, List)}.
 */
public final class SignalTraceComputer {
    private SignalTraceComputer() {}

    public static SignalTrace compute(BlochData data, List<PulseSegment> pulse) {
        return computeAll(data, pulse).primary();
    }

    public static MultiCoilSignalTrace computeAll(BlochData data, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return MultiCoilSignalTrace.empty();
        var f = data.field();
        var receiveCoils = f.receiveCoils == null ? List.<ReceiveCoilMap>of() : f.receiveCoils;
        if (receiveCoils.isEmpty()) return MultiCoilSignalTrace.empty();

        var rArr = f.rMm;
        var zArr = f.zMm;

        var points = new ArrayList<FieldPoint>();
        for (int ir = 0; ir < rArr.length; ir++) {
            for (int iz = 0; iz < zArr.length; iz++) {
                points.add(FieldInterpolator.interpolate(f, rArr[ir], zArr[iz]));
            }
        }
        if (points.isEmpty()) return MultiCoilSignalTrace.empty();

        int np = points.size();
        var mx = new double[np];
        var my = new double[np];
        var mz = new double[np];
        for (int p = 0; p < np; p++) mz[p] = 1;

        int nCoils = receiveCoils.size();
        var traces = new ArrayList<List<Point>>(nCoils);
        double[] cosPhase = new double[nCoils];
        double[] sinPhase = new double[nCoils];
        for (int k = 0; k < nCoils; k++) {
            traces.add(new ArrayList<>());
            traces.get(k).add(new Point(0, 0, 0));
            double rad = receiveCoils.get(k).phaseDeg * Math.PI / 180.0;
            cosPhase[k] = Math.cos(rad);
            sinPhase[k] = Math.sin(rad);
        }

        double t = 0;
        int dynamicCount = f.dynamicFields == null ? 0 : f.dynamicFields.size();

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double e2 = Math.exp(-seg.dt() / f.t2);
            double e1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                boolean rfOn = step.isRfOn();
                var controls = step.controls();
                for (int p = 0; p < np; p++) {
                    var fp = points.get(p);
                    double bx = 0, by = 0;
                    double bz = fp.staticBz();
                    for (int i = 0; i < dynamicCount; i++) {
                        var df = f.dynamicFields.get(i);
                        double ex = fp.ex()[i];
                        double ey = fp.ey()[i];
                        double ez = fp.ez()[i];
                        if (df.channelCount == 1) {
                            double amp = controls[df.channelOffset];
                            bx += amp * ex;
                            by += amp * ey;
                            bz += amp * ez;
                        } else {
                            double ampI = controls[df.channelOffset];
                            double ampQ = controls[df.channelOffset + 1];
                            if (Math.abs(df.deltaOmega) > 1e-9) {
                                double phase = df.deltaOmega * t;
                                double c = Math.cos(phase);
                                double s = Math.sin(phase);
                                double iRot = ampI * c - ampQ * s;
                                double qRot = ampI * s + ampQ * c;
                                ampI = iRot;
                                ampQ = qRot;
                            }
                            bx += ampI * ex - ampQ * ey;
                            by += ampI * ey + ampQ * ex;
                            bz += ampI * ez;
                        }
                    }

                    double bPerp2 = bx * bx + by * by;
                    if (!rfOn && bPerp2 < 1e-30) {
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
                for (int k = 0; k < nCoils; k++) {
                    var coil = receiveCoils.get(k);
                    double srAcc = 0, siAcc = 0;
                    for (int p = 0; p < np; p++) {
                        var fp = points.get(p);
                        double eX = fp.rxEx()[k];
                        double eY = fp.rxEy()[k];
                        srAcc += eX * mx[p] + eY * my[p];
                        siAcc += eX * my[p] - eY * mx[p];
                    }
                    double phasedR = (srAcc * cosPhase[k] - siAcc * sinPhase[k]) * coil.gain;
                    double phasedI = (srAcc * sinPhase[k] + siAcc * cosPhase[k]) * coil.gain;
                    traces.get(k).add(new Point(tUs, phasedR, phasedI));
                }
            }
        }

        var byCoil = new LinkedHashMap<String, SignalTrace>();
        for (int k = 0; k < nCoils; k++) {
            byCoil.put(receiveCoils.get(k).name, new SignalTrace(List.copyOf(traces.get(k))));
        }
        return new MultiCoilSignalTrace(Map.copyOf(byCoil), receiveCoils.get(0).name);
    }
}
