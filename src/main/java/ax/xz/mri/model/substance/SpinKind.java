package ax.xz.mri.model.substance;

/**
 * What the spins of a {@link Substance} are, physically. Used by
 * {@link ax.xz.mri.service.simulation.compiled.CompiledSimulation} during
 * compile-time dispatch and by procedures that want to specialise on
 * different physics.
 */
public enum SpinKind {
    /** Bloch-vector spin: 3-vector magnetisation, T1/T2 relaxation. Continuous magnetisation. */
    BLOCH,
    /** NV centre triplet ground state: full 3×3 density matrix or 2-level reduction. */
    NV
}
