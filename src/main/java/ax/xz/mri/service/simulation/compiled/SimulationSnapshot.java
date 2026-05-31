package ax.xz.mri.service.simulation.compiled;

/**
 * Opaque snapshot of {@link CompiledSimulation}'s mutable state.
 *
 * <p>Used by procedure code and UI scrubbing to capture / restore the
 * simulator's running state without exposing the fused state vector
 * layout. Snapshots are immutable: a restored snapshot puts the
 * simulator back into the exact state at which it was taken.
 */
public final class SimulationSnapshot {
    final double[] state;
    final double[] sCoilRePrev;
    final double[] sCoilImPrev;
    final double timeSeconds;
    final int stepIndex;

    SimulationSnapshot(double[] state, double[] sCoilRePrev, double[] sCoilImPrev,
                       double timeSeconds, int stepIndex) {
        this.state = state.clone();
        this.sCoilRePrev = sCoilRePrev.clone();
        this.sCoilImPrev = sCoilImPrev.clone();
        this.timeSeconds = timeSeconds;
        this.stepIndex = stepIndex;
    }
}
