package ax.xz.mri.model.simulation;

/**
 * 2-D heatmap: phase and transverse magnetisation over (time × one spatial axis).
 * {@code data[iy]} is a row of time-ordered cells for the iy-th spatial position.
 */
public record PhaseMapData(double[] yArr, Cell[][] data, int nY) {

    public record Cell(double tMicros, double phaseDeg, double mPerp) {}
}
