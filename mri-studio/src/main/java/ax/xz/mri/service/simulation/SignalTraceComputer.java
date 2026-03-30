package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the coherent signal trace by integrating the Bloch equations
 * across all in-slice spatial grid points.
 * Port of {@code compSignalTrace()} from physics.ts.
 */
public final class SignalTraceComputer {
    private SignalTraceComputer() {}

    public static SignalTrace compute(BlochData data, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return null;
        var f     = data.field();
        var rArr  = f.rMm;
        var zArr  = f.zMm;
        double sliceMm = (f.sliceHalf != null ? f.sliceHalf : 0.005) * 1e3;

        // Collect in-slice grid points
        record Pt(double dBz, double gxm, double gzm, double b1s) {}
        var pts = new ArrayList<Pt>();
        for (int ir = 0; ir < rArr.length; ir++) {
            for (int iz = 0; iz < zArr.length; iz++) {
                if (Math.abs(zArr[iz]) > sliceMm) continue;
                double rM = rArr[ir] * 1e-3;
                double zM = zArr[iz] * 1e-3;
                double B  = f.b0n;
                pts.add(new Pt(
                    f.dBzUt[ir][iz] * 1e-6,
                    rM + zM * zM / (2 * B),
                    zM + (rM / 2) * (rM / 2) / (2 * B),
                    1 + 0.12 * sq(rM / (f.fovX / 2)) + 0.08 * sq(zM / (f.fovZ / 2))
                ));
            }
        }
        if (pts.isEmpty()) return null;

        int np  = pts.size();
        var mx  = new double[np];
        var my  = new double[np];
        var mz  = new double[np];
        for (int p = 0; p < np; p++) mz[p] = 1;

        var trace = new ArrayList<Point>();
        trace.add(new Point(0, 0));
        double t = 0;

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg   = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double E2 = Math.exp(-seg.dt() / f.t2);
            double E1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                boolean rfOn = step.isRfOn();
                for (int p = 0; p < np; p++) {
                    var pt = pts.get(p);
                    if (!rfOn) {
                        double om  = f.gamma * (pt.dBz() + step.gx() * pt.gxm() + step.gz() * pt.gzm());
                        double th  = om * seg.dt();
                        double c   = Math.cos(th), s = Math.sin(th);
                        double nmx = (mx[p] * c - my[p] * s) * E2;
                        double nmy = (mx[p] * s + my[p] * c) * E2;
                        mx[p] = nmx; my[p] = nmy;
                        mz[p] = 1 + (mz[p] - 1) * E1;
                    } else {
                        double bx = step.b1x() * pt.b1s();
                        double by = step.b1y() * pt.b1s();
                        double bz = pt.dBz() + step.gx() * pt.gxm() + step.gz() * pt.gzm();
                        double Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
                        double th = f.gamma * Bm * seg.dt();
                        double nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
                        double c  = Math.cos(th), s = Math.sin(th), oc = 1 - c;
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
                    for (int p = 0; p < np; p++) { sx += mx[p]; sy += my[p]; }
                }
                double tUs = Math.round(t * 1e7) / 10.0;
                trace.add(new Point(tUs, Math.sqrt(sx * sx + sy * sy) / np));
            }
        }
        return new SignalTrace(trace);
    }

    private static double sq(double x) { return x * x; }
}
