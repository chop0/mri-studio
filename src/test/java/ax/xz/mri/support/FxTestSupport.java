package ax.xz.mri.support;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/** Minimal JavaFX test harness for construction/disposal smoke tests. */
public final class FxTestSupport {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private FxTestSupport() {
    }

    public static void runOnFxThread(ThrowingRunnable runnable) {
        startToolkit();
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finished.countDown();
            }
        });
        try {
            if (!finished.await(10, TimeUnit.SECONDS)) {
                fail("Timed out waiting for JavaFX test block");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(ex);
        }
        if (failure.get() != null) {
            fail(failure.get());
        }
    }

    public static void startToolkit() {
        if (!STARTED.compareAndSet(false, true)) return;

        var started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Some other test or a SkillsCheck already booted the toolkit. That's fine.
            return;
        }
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                fail("Timed out starting JavaFX toolkit");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(ex);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
