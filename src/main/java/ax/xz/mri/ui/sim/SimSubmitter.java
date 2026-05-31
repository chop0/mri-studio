package ax.xz.mri.ui.sim;

import java.util.function.Consumer;

/**
 * Abstraction over the simulation worker. {@link SimRunner} is the production
 * implementation; tests substitute a synchronous fake to exercise the
 * dispatcher's coalescing and cancellation logic without spinning up a real
 * simulator.
 */
public interface SimSubmitter {
    void submit(SimRequest request, Consumer<SimResult> onResult, Consumer<Throwable> onError);
    void dispose();
}
