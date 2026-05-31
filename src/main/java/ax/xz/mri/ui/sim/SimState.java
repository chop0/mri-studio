package ax.xz.mri.ui.sim;

/**
 * Lifecycle state of the simulation pipeline, visible to the editor toolbar
 * and status bar. {@code Pending} is the debounce window between a dirty
 * signal and the runner picking up the request; {@code Running} is while the
 * runner thread is computing; {@code Failed} carries a human-readable
 * message; {@code Idle} is anything else.
 */
public sealed interface SimState {
    record Idle() implements SimState {}
    record Pending() implements SimState {}
    record Running() implements SimState {}
    record Failed(String message) implements SimState {}

    SimState IDLE = new Idle();
    SimState PENDING = new Pending();
    SimState RUNNING = new Running();
}
