package ax.xz.mri.model.simulation.dsl;

/**
 * A compilation error reported by the Janino-backed eigenfield script engine.
 *
 * <p>Carries source location so the editor can place a red squiggle exactly
 * where Janino rejected the input. {@code line} and {@code column} are 1-based
 * (line 0 / column 0 means "unknown").
 */
public final class ScriptCompileException extends RuntimeException {
    private final int line;
    private final int column;
    private final String shortMessage;

    public ScriptCompileException(String shortMessage, int line, int column, Throwable cause) {
        super(shortMessage + " (line " + line + ", col " + column + ")", cause);
        this.shortMessage = shortMessage;
        this.line = line;
        this.column = column;
    }

    public int line() { return line; }
    public int column() { return column; }
    public String shortMessage() { return shortMessage; }
}
