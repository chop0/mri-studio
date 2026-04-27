package ax.xz.mri.service.simulation;

import ax.xz.mri.model.scenario.SimulationOutput;
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
    public static PhaseMapData computePhaseZ(SimulationOutput data, List<PulseSegment> pulse) {
        int nZ = 50;
        var yArr = new double[nZ];
        for (int i = 0; i < nZ; i++) yArr[i] = -6 + 12.0 * i / (nZ - 1);
        return compute(data, pulse, yArr, nZ, true);
    }

    /** Phase(r, t) heatmap: 20 r positions from 0 to 30 mm at z = 0. */
    public static PhaseMapData computePhaseR(SimulationOutput data, List<PulseSegment> pulse) {
        int nR = 20;
        var yArr = new double[nR];
        for (int i = 0; i < nR; i++) yArr[i] = 30.0 * i / (nR - 1);
        return compute(data, pulse, yArr, nR, false);
    }

    private static PhaseMapData compute(SimulationOutput data, List<PulseSegment> pulse,
                                        double[] yArr, int nY, boolean varyZ) {
        var times = new double[nY][];
        var phases = new double[nY][];
        var magnitudes = new double[nY][];
        var mxRows = new double[nY][];
        var myRows = new double[nY][];
        int maxColumns = 0;

        for (int iy = 0; iy < nY; iy++) {
            double r = varyZ ? 0 : yArr[iy];
            double z = varyZ ? yArr[iy] : 0;
            var traj = BlochSimulator.simulate(data, r, z, pulse);
            if (traj == null) {
                times[iy] = new double[0];
                phases[iy] = new double[0];
                magnitudes[iy] = new double[0];
                mxRows[iy] = new double[0];
                myRows[iy] = new double[0];
                continue;
            }

            int n = traj.pointCount();
            int nc = 0;
            for (int it = 0; it * STEP < n; it++) nc++;
            times[iy] = new double[nc];
            phases[iy] = new double[nc];
            magnitudes[iy] = new double[nc];
            mxRows[iy] = new double[nc];
            myRows[iy] = new double[nc];
            for (int it = 0; it < nc; it++) {
                int j  = it * STEP;
                double t  = traj.tAt(j);
                double mx = traj.mxAt(j), my = traj.myAt(j);
                double ph = Math.atan2(my, mx) * 180.0 / Math.PI;
                double mp = Math.sqrt(mx * mx + my * my);
                times[iy][it] = t;
                phases[iy][it] = ph;
                magnitudes[iy][it] = mp;
                mxRows[iy][it] = mx;
                myRows[iy][it] = my;
            }
            maxColumns = Math.max(maxColumns, nc);
        }

        var signalProjection = new double[nY][];
        for (int iy = 0; iy < nY; iy++) {
            signalProjection[iy] = new double[magnitudes[iy].length];
        }
        for (int columnIndex = 0; columnIndex < maxColumns; columnIndex++) {
            double sumMx = 0;
            double sumMy = 0;
            for (int rowIndex = 0; rowIndex < nY; rowIndex++) {
                if (columnIndex >= mxRows[rowIndex].length) continue;
                sumMx += mxRows[rowIndex][columnIndex];
                sumMy += myRows[rowIndex][columnIndex];
            }
            double sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
            double ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
            double uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;
            for (int rowIndex = 0; rowIndex < nY; rowIndex++) {
                if (columnIndex >= mxRows[rowIndex].length) continue;
                signalProjection[rowIndex][columnIndex] =
                    Math.max(0, mxRows[rowIndex][columnIndex] * ux + myRows[rowIndex][columnIndex] * uy);
            }
        }

        var rows = new Cell[nY][];
        for (int iy = 0; iy < nY; iy++) {
            rows[iy] = new Cell[magnitudes[iy].length];
            for (int it = 0; it < magnitudes[iy].length; it++) {
                rows[iy][it] = new Cell(times[iy][it], phases[iy][it], magnitudes[iy][it], signalProjection[iy][it]);
            }
        }
        return new PhaseMapData(yArr, rows, nY);
    }
}
