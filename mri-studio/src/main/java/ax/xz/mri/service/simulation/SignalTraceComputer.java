package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the coherent signal trace by integrating the Bloch equations
 * across all in-slice spatial grid points.
 */
public final class SignalTraceComputer {
    private SignalTraceComputer() {}

    public static SignalTrace compute(BlochData data, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return null;
        var f = data.field();
        var rArr = f.rMm;
        var zArr = f.zMm;
        double sliceMm = (f.sliceHalf != null ? f.sliceHalf : 0.005) * 1e3;

        record Pt(FieldPoint fp) {}
        var pts = new ArrayList<Pt>();
        for (int ir = 0; ir < rArr.length; ir++) {
            for (int iz = 0; iz < zArr.length; iz++) {
                if (Math.abs(zArr[iz]) > sliceMm) continue;
                pts.add(new Pt(FieldInterpolator.interpolate(f, rArr[ir], zArr[iz])));
            }
        }
        if (pts.isEmpty()) return null;

        int np = pts.size();
        var mx = new double[np];
        var my = new double[np];
        var mz = new double[np];
        for (int p = 0; p < np; p++) mz[p] = 1;

        var trace = new ArrayList<Point>();
        trace.add(new Point(0, 0));
        double t = 0;

        int dynamicCount = f.dynamicFields == null ? 0 : f.dynamicFields.size();

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double E2 = Math.exp(-seg.dt() / f.t2);
            double E1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                boolean rfOn = step.isRfOn();
                var controls = step.controls();
                for (int p = 0; p < np; p++) {
                    var fp = pts.get(p).fp();
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
                        double nmx = (mx[p] * c - my[p] * s) * E2;
                        double nmy = (mx[p] * s + my[p] * c) * E2;
                        mx[p] = nmx;
                        my[p] = nmy;
                        mz[p] = 1 + (mz[p] - 1) * E1;
                    } else {
                        double Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
                        double th = f.gamma * Bm * seg.dt();
                        double nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
                        double c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
                        double nd = nx * mx[p] + ny * my[p] + nz * mz[p];
                        double cx = ny * mz[p] - nz * my[p];
                        double cy = nz * mx[p] - nx * mz[p];
                        double cz = nx * my[p] - ny * mx[p];
                        mx[p] = (mx[p] * c + cx * s + nx * nd * oc) * E2;
                        my[p] = (my[p] * c + cy * s + ny * nd * oc) * E2;
                        mz[p] = 1 + (mz[p] * c + cz * s + nz * nd * oc - 1) * E1;
                    }
                }
                t += seg.dt();
                double sx = 0, sy = 0;
                if (!rfOn) {
                    for (int p = 0; p < np; p++) {
                        sx += mx[p];
                        sy += my[p];
                    }
                }
                double tUs = Math.round(t * 1e7) / 10.0;
                trace.add(new Point(tUs, Math.sqrt(sx * sx + sy * sy) / np));
            }
        }
        return new SignalTrace(trace);
    }
}
