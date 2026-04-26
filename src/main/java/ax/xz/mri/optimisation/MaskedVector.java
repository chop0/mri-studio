package ax.xz.mri.optimisation;

/**
 * Index map from a full parameter vector down to its "free" sub-vector
 * (under a boolean mask) and back again.
 *
 * <p>Used by the masked optimiser to project gradients/positions onto the
 * parameters that are actually being optimised, leaving frozen parameters
 * untouched.
 */
public record MaskedVector(int[] freeIndices) {
    public MaskedVector {
        freeIndices = freeIndices.clone();
    }

    /** Build from a boolean mask: {@code true} entries are free. */
    public static MaskedVector of(boolean[] freeMask) {
        int count = 0;
        for (boolean v : freeMask) if (v) count++;
        int[] indices = new int[count];
        int offset = 0;
        for (int i = 0; i < freeMask.length; i++) if (freeMask[i]) indices[offset++] = i;
        return new MaskedVector(indices);
    }

    /** Number of free parameters. */
    public int size() { return freeIndices.length; }

    /** Pick the free entries out of a full-length vector. */
    public double[] pick(double[] full) {
        double[] out = new double[freeIndices.length];
        for (int i = 0; i < freeIndices.length; i++) out[i] = full[freeIndices[i]];
        return out;
    }

    /** Merge a free sub-vector back into a clone of {@code frozenFull}, leaving frozen entries unchanged. */
    public double[] merge(double[] frozenFull, double[] freeValues) {
        double[] merged = frozenFull.clone();
        for (int i = 0; i < freeIndices.length; i++) merged[freeIndices[i]] = freeValues[i];
        return merged;
    }
}
