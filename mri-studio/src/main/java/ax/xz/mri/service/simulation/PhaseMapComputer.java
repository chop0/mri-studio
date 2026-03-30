package ax.xz.mri.service.simulation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.model.simulation.PhaseMapData.Cell;

import java.util.List;

/**
 * Computes phase-vs-time heatmaps.
 * Port of {@code compPhaseZ()} and {@code compPhaseR()} from physics.ts.
 */
public final class PhaseMapComputer {
    private static final int STEP = 4;  // subsample every 4th trajectory point
    private PhaseMapComputer() {}

    /** Phase(z, t) heatmap: 50 z positions from −6 mm to +6 mm at r = 0. */
    public static PhaseMapData computePhaseZ(BlochData data, List<PulseSegment> pulse) {
        int nZ = 50;
        var yArr = new double[nZ];
        for (int i = 0; i < nZ; i++) yArr[i] = -6 + 12.0 * i / (nZ - 1);
        return compute(data, pulse, yArr, nZ, true);
    }

    /** Phase(r, t) heatmap: 20 r positions from 0 to 30 mm at z = 0. */
    public static PhaseMapData computePhaseR(BlochData data, List<PulseSegment> pulse) {
        int nR = 20;
        var yArr = new double[nR];
        for (int i = 0; i < nR; i++) yArr[i] = 30.0 * i / (nR - 1);
        return compute(data, pulse, yArr, nR, false);
    }

    private static PhaseMapData compute(BlochData data, List<PulseSegment> pulse,
                                        double[] yArr, int nY, boolean varyZ) {
        var rows = new Cell[nY][];
        for (int iy = 0; iy < nY; iy++) {
            double r = varyZ ? 0 : yArr[iy];
            double z = varyZ ? yArr[iy] : 0;
            var traj = BlochSimulator.simulate(data, r, z, pulse);
            if (traj == null) { rows[iy] = new Cell[0]; continue; }

            int n = traj.pointCount();
            int nc = 0;
            for (int it = 0; it * STEP < n; it++) nc++;
            var row = new Cell[nc];
            for (int it = 0; it < nc; it++) {
                int j  = it * STEP;
                double t  = traj.tAt(j);
                double mx = traj.mxAt(j), my = traj.myAt(j);
                double ph = Math.atan2(my, mx) * 180.0 / Math.PI;
                double mp = Math.sqrt(mx * mx + my * my);
                row[it] = new Cell(t, ph, mp);
            }
            rows[iy] = row;
        }
        return new PhaseMapData(yArr, rows, nY);
    }
}
