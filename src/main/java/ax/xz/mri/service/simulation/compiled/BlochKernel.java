package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.field.SpatialGrid;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.service.simulation.math.BlochStep;

/**
 * {@link CompiledSubstance} for {@link ContinuousMagnetisation}: one Bloch
 * spin per FOV voxel.
 *
 * <p>State layout in the fused state vector slice:
 * {@code [mx0, my0, mz0, mx1, my1, mz1, …]} — 3 reals per voxel. The slice
 * length is {@code 3 · grid.size()}; spin {@code i} reads from {@code state
 * [offset + 3i .. offset + 3i + 2]}.
 *
 * <p>Reset goes to {@code (0, 0, mz0)} — thermal equilibrium pointing along
 * the rotating-frame z. Per-step advance is the existing Rodrigues +
 * exponential-decay kernel in {@link BlochStep}. Magnetic-moment emission is
 * the raw {@code (mx, my, mz)}; the reciprocity weight comes from the
 * per-spin coil sensitivity baked into {@link CompiledSimulation}.
 */
public final class BlochKernel implements CompiledSubstance {

    private final ContinuousMagnetisation source;
    private final SpatialGrid grid;
    private final int spinCount;

    BlochKernel(ContinuousMagnetisation source, SpatialGrid grid) {
        this.source = source;
        this.grid = grid;
        this.spinCount = grid.size();
    }

    @Override public Substance source() { return source; }
    @Override public int spinCount() { return spinCount; }
    @Override public Vec3 spinPosition(int i) { return grid.position(i); }
    @Override public int stateSize() { return 3 * spinCount; }

    @Override
    public void reset(double[] state, int offset) {
        double mz0 = source.mz0();
        for (int i = 0; i < spinCount; i++) {
            int base = offset + 3 * i;
            state[base    ] = 0.0;
            state[base + 1] = 0.0;
            state[base + 2] = mz0;
        }
    }

    @Override
    public void advance(double[] state, int offset,
                        double[] localBField, double[] controlInputs,
                        double dt, double tSeconds) {
        // Bloch substance has no control inputs — controlInputs is empty.
        double gamma = source.gammaRadPerSecPerTesla();
        double e1 = Math.exp(-dt / source.t1Seconds());
        double e2 = Math.exp(-dt / source.t2Seconds());

        for (int i = 0; i < spinCount; i++) {
            int sb = offset + 3 * i;
            int fb = 3 * i;
            double bx = localBField[fb];
            double by = localBField[fb + 1];
            double bz = localBField[fb + 2];

            double mxp = state[sb], myp = state[sb + 1], mzp = state[sb + 2];
            if ((bx * bx + by * by) < BlochStep.B_PERP_SQ_FLOOR) {
                // z-only specialisation — bit-identical to BlochStep.zOnly.
                double th = gamma * bz * dt;
                double c = Math.cos(th), s = Math.sin(th);
                state[sb    ] = (mxp * c - myp * s) * e2;
                state[sb + 1] = (mxp * s + myp * c) * e2;
                state[sb + 2] = 1.0 + (mzp - 1.0) * e1;
            } else {
                // Full Rodrigues — bit-identical to BlochStep.rodrigues.
                double bm = Math.sqrt(bx * bx + by * by + bz * bz + ax.xz.mri.model.hardware.HardwareLimits.EPSILON);
                double nx = bx / bm, ny = by / bm, nz = bz / bm;
                double th = gamma * bm * dt;
                double c = Math.cos(th), s = Math.sin(th), omc = 1.0 - c;
                double nd = nx * mxp + ny * myp + nz * mzp;
                double cx = ny * mzp - nz * myp;
                double cy = nz * mxp - nx * mzp;
                double cz = nx * myp - ny * mxp;
                state[sb    ] = (mxp * c + cx * s + nx * nd * omc) * e2;
                state[sb + 1] = (myp * c + cy * s + ny * nd * omc) * e2;
                state[sb + 2] = 1.0 + (mzp * c + cz * s + nz * nd * omc - 1.0) * e1;
            }
        }
    }

    @Override
    public void emitMagneticMoments(double[] state, int offset, double[] momentsOut) {
        // Continuous magnetisation: moment per voxel = (mx, my, mz). Voxel
        // volume weight is implicitly 1 (uniform); changing this scales the
        // received signal magnitude but not its dynamics. We keep the legacy
        // convention so SignalTraceComputer parity holds bit-for-bit.
        System.arraycopy(state, offset, momentsOut, 0, 3 * spinCount);
    }
}
