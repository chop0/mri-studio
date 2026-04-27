package ax.xz.mri.hardware;

/**
 * Checked exception thrown by {@link HardwareDevice} operations — connection
 * failures, run aborts, capability mismatches, malformed device responses.
 *
 * <p>Plugins should throw this with a user-readable message; the runner
 * surfaces it through the messages pane.
 */
public class HardwareException extends Exception {
    public HardwareException(String message) { super(message); }
    public HardwareException(String message, Throwable cause) { super(message, cause); }
}
