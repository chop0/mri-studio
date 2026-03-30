package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;

import java.util.List;

/**
 * Core Bloch equation simulator.
 * Exact port of {@code sim()} and {@code simTo()} from physics.ts.
 */
public final class BlochSimulator {
    private BlochSimulator() {}

    /**
     * Simulate the full trajectory from thermal equilibrium through all segments.
     *
     * @return trajectory with one point per pulse step, or null if data is insufficient
     */
    public static Trajectory simulate(BlochData data, double rMm, double zMm,
                                      List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return null;
        var f  = data.field();
        var fl = FieldInterpolator.interpolate(f, rMm, zMm);

        double mx = fl.mx0(), my = fl.my0(), mz = fl.mz0();
        double t  = 0;

        int totalPoints = pulse.stream().mapToInt(s -> s.size()).sum() + 1;
        var out = new double[totalPoints * 5];
        int oi  = 0;

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg   = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double E2 = Math.exp(-seg.dt() / f.t2);
            double E1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                out[oi++] = round1(t * 1e6);
                out[oi++] = round5(mx);
                out[oi++] = round5(my);
                out[oi++] = round5(mz);
                out[oi++] = step.isRfOn() ? 1 : 0;

                if (!step.isRfOn()) {
                    double om  = f.gamma * (fl.dBz() + step.gx() * fl.gxm() + step.gz() * fl.gzm());
                    double th  = om * seg.dt();
                    double c   = Math.cos(th), s = Math.sin(th);
                    double nmx = (mx * c - my * s) * E2;
                    double nmy = (mx * s + my * c) * E2;
                    mx = nmx; my = nmy;
                    mz = 1 + (mz - 1) * E1;
                } else {
                    double[] r = rodrigues(step, fl, f, seg.dt(), mx, my, mz, E2, E1);
                    mx = r[0]; my = r[1]; mz = r[2];
                }
                t += seg.dt();
            }
        }
        // final state
        out[oi++] = round1(t * 1e6);
        out[oi++] = round5(mx);
        out[oi++] = round5(my);
        out[oi++] = round5(mz);
        out[oi]   = 2; // sentinel
        return new Trajectory(out);
    }

    /**
     * Simulate up to {@code tcMicros} and return the magnetisation at that time.
     * Early-exits as soon as the cursor time is reached; much faster for cross-section rendering.
     */
    public static MagnetisationState simulateTo(BlochData data, double rMm, double zMm,
                                                List<PulseSegment> pulse, double tcMicros) {
        if (data == null || data.field() == null || pulse == null)
            return MagnetisationState.THERMAL_EQUILIBRIUM;
        var f  = data.field();
        var fl = FieldInterpolator.interpolate(f, rMm, zMm);

        double mx = fl.mx0(), my = fl.my0(), mz = fl.mz0();
        double t  = 0;

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg   = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double E2 = Math.exp(-seg.dt() / f.t2);
            double E1 = Math.exp(-seg.dt() / f.t1);

            for (var step : steps) {
                if (t * 1e6 >= tcMicros) return new MagnetisationState(mx, my, mz);

                if (!step.isRfOn()) {
                    double om  = f.gamma * (fl.dBz() + step.gx() * fl.gxm() + step.gz() * fl.gzm());
                    double th  = om * seg.dt();
                    double c   = Math.cos(th), s = Math.sin(th);
                    double nmx = (mx * c - my * s) * E2;
                    double nmy = (mx * s + my * c) * E2;
                    mx = nmx; my = nmy;
                    mz = 1 + (mz - 1) * E1;
                } else {
                    double[] r = rodrigues(step, fl, f, seg.dt(), mx, my, mz, E2, E1);
                    mx = r[0]; my = r[1]; mz = r[2];
                }
                t += seg.dt();
            }
        }
        return new MagnetisationState(mx, my, mz);
    }

    // ── Rodrigues rotation ───────────────────────────────────────────────────

    private static double[] rodrigues(PulseStep step, FieldPoint fl, FieldMap f,
                                      double dt, double mx, double my, double mz,
                                      double E2, double E1) {
        double bx = step.b1x() * fl.b1s();
        double by = step.b1y() * fl.b1s();
        double bz = fl.dBz() + step.gx() * fl.gxm() + step.gz() * fl.gzm();
        double Bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
        double th = f.gamma * Bm * dt;
        double nx = bx / Bm, ny = by / Bm, nz = bz / Bm;
        double c  = Math.cos(th), s = Math.sin(th), oc = 1 - c;
        double nd = nx * mx + ny * my + nz * mz;
        double cx = ny * mz - nz * my;
        double cy = nz * mx - nx * mz;
        double cz = nx * my - ny * mx;
        return new double[]{
            (mx * c + cx * s + nx * nd * oc) * E2,
            (my * c + cy * s + ny * nd * oc) * E2,
            1 + (mz * c + cz * s + nz * nd * oc - 1) * E1
        };
    }

    private static double round1(double v) { return Math.round(v * 10)  / 10.0; }
    private static double round5(double v) { return Math.round(v * 1e5) / 1e5;  }
}
