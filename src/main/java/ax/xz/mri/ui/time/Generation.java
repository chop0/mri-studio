package ax.xz.mri.ui.time;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonically-increasing counter handed to async work for cancellation. A
 * caller captures {@link #current()} on submit and, when its result is ready,
 * checks {@link #isCurrent(long)}; if the counter has advanced the result is
 * stale and discarded. {@link #bump()} is called whenever the input state
 * changes in a way that invalidates in-flight work.
 */
public final class Generation {
    private final AtomicLong counter = new AtomicLong();

    public long current() {
        return counter.get();
    }

    public long bump() {
        return counter.incrementAndGet();
    }

    public boolean isCurrent(long captured) {
        return captured == counter.get();
    }
}
