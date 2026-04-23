package ax.xz.mri.optimisation;

import ax.xz.mri.service.circuit.CompiledCircuit;

/**
 * Flat per-point spatial data for the optimiser plus the circuit it's
 * evaluating against.
 *
 * <p>The flat arrays ({@link #mx0}, {@link #my0}, {@link #mz0},
 * {@link #staticBz}, {@link #wOut}, {@link #coilExFlat}, …) are sized
 * {@code nr × nz} so the inner loop has zero cache misses per point. The
 * {@link #circuit compiled circuit} provides the source / switch / probe
 * topology the inner loop consults each step.
 */
public record ProblemGeometry(
    double[] mx0,
    double[] my0,
    double[] mz0,
    double[] staticBz,
    double[] wOut,
    double[][] coilExFlat,
    double[][] coilEyFlat,
    double[][] coilEzFlat,
    CompiledCircuit circuit,
    double gamma,
    double t1,
    double t2,
    int nr,
    int nz
) {
    public ProblemGeometry {
        if (circuit == null) throw new IllegalArgumentException("ProblemGeometry.circuit must not be null");
        int count = staticBz.length;
        if (mx0.length != count || my0.length != count || mz0.length != count || wOut.length != count)
            throw new IllegalArgumentException("Geometry arrays must share a length");
        if (coilExFlat.length != circuit.coils().size())
            throw new IllegalArgumentException("coilExFlat must be length circuit.coils().size()");
        for (var arr : coilExFlat) if (arr.length != count)
            throw new IllegalArgumentException("coilExFlat rows must match point count");
        if (nr <= 0 || nz <= 0) throw new IllegalArgumentException("nr, nz must be positive");
    }

    public int pointCount() { return staticBz.length; }

    public int primaryProbeIndex() {
        return circuit.probes().isEmpty() ? -1 : 0;
    }
}
