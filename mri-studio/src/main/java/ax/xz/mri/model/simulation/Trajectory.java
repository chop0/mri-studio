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
        for (int i = 0; i < n - 1; i++) {
            double tA = tAt(i), tB = tAt(i + 1);
            if (tB >= tcMicros) {
                double f = (tB == tA) ? 0.0 : (tcMicros - tA) / (tB - tA);
                return new MagnetisationState(
                    mxAt(i) + f * (mxAt(i + 1) - mxAt(i)),
                    myAt(i) + f * (myAt(i + 1) - myAt(i)),
                    mzAt(i) + f * (mzAt(i + 1) - mzAt(i))
                );
            }
        }
        int last = n - 1;
        return new MagnetisationState(mxAt(last), myAt(last), mzAt(last));
    }
}
