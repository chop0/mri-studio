package ax.xz.mri.dsl;

/**
 * Wraps runtime errors thrown by a user-authored script so callers can
 * distinguish them from {@link ScriptCompileException compile-time errors}.
 */
public final class ScriptRuntimeException extends RuntimeException {
    public ScriptRuntimeException(Throwable cause) { super(cause); }
    public ScriptRuntimeException(String message, Throwable cause) { super(message, cause); }
}
