package ax.xz.mri.dsl;

import java.util.List;

/**
 * Engine singleton for procedure scripts. Same shape as
 * {@link EigenfieldEngine} — wraps a {@link ScriptEngine} configured for
 * {@link Script}. Users write a full compilation unit and pull in whatever
 * they need (typically {@code import module ax.xz.mri;}).
 *
 * <p>"Procedure" survives as a project-side category (the document filed
 * under {@code procedures/} in the user's mri-project) — this compiler is
 * the bridge between that document's source and the runtime contract,
 * which is just {@link Script}.
 */
public final class ProcedureEngine {
    private ProcedureEngine() {}

    private static final ScriptEngine<Script> ENGINE =
        new ScriptEngine<>(Script.class, List.of());

    public static Script compile(String source) {
        return ENGINE.compile(source);
    }

    public static Script compileUncached(String source) {
        return ENGINE.compileUncached(source);
    }

    public static void clearCache() {
        ENGINE.clearCache();
    }
}
