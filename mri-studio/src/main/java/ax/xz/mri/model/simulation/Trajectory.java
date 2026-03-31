package ax.xz.mri.model.simulation;

/**
 * Flat double[] in groups of 5: [t_μs, Mx, My, Mz, isRF] per point.
 * isRF = 1.0 indicates an RF-on step; 0.0 indicates free precession.
 */
public record Trajectory(double[] data) {

    public int     pointCount()    { return data.length / 5; }
    public double  tAt(int i)      { return data[i * 5]; }
    public double  mxAt(int i)     { return data[i * 5 + 1]; }
    public double  myAt(int i)     { return data[i * 5 + 2]; }
    public double  mzAt(int i)     { return data[i * 5 + 3]; }
    public boolean isRfAt(int i)   { return data[i * 5 + 4] >= 0.5; }

    /**
     * Interpolate [Mx, My, Mz] at {@code tcMicros}.
     * Returns null when the trajectory has fewer than 2 points.
     */
    public MagnetisationState interpolateAt(double tcMicros) {
        int n = pointCount();
        if (n < 2) return null;
        int rightIndex = firstIndexWithTimeAtLeast(tcMicros);
        if (rightIndex <= 0) {
            return new MagnetisationState(mxAt(0), myAt(0), mzAt(0));
        }
        if (rightIndex >= n) {
            int last = n - 1;
            return new MagnetisationState(mxAt(last), myAt(last), mzAt(last));
        }
        int leftIndex = rightIndex - 1;
        double tA = tAt(leftIndex);
        double tB = tAt(rightIndex);
        double f = (tB == tA) ? 0.0 : (tcMicros - tA) / (tB - tA);
        return new MagnetisationState(
            mxAt(leftIndex) + f * (mxAt(rightIndex) - mxAt(leftIndex)),
            myAt(leftIndex) + f * (myAt(rightIndex) - myAt(leftIndex)),
            mzAt(leftIndex) + f * (mzAt(rightIndex) - mzAt(leftIndex))
        );
    }

    /**
     * Return the discrete step state used by {@code simulateTo()} semantics:
     * the first sampled state whose timestamp is greater than or equal to {@code tcMicros}.
     */
    public MagnetisationState stepStateAt(double tcMicros) {
        int n = pointCount();
        if (n == 0) return MagnetisationState.THERMAL_EQUILIBRIUM;
        int index = Math.min(Math.max(firstIndexWithTimeAtLeast(tcMicros), 0), n - 1);
        return new MagnetisationState(mxAt(index), myAt(index), mzAt(index));
    }

    private int firstIndexWithTimeAtLeast(double tcMicros) {
        int low = 0;
        int high = pointCount() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            double tMid = tAt(mid);
            if (tMid < tcMicros) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }
}
