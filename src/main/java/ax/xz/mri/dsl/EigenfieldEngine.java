package ax.xz.mri.dsl;

import java.util.List;

/**
 * Engine singleton for eigenfield scripts. Hands user-authored Java classes
 * through {@link ScriptEngine} with no engine-side import injection — the
 * user writes a complete compilation unit and brings in whatever they need
 * (typically {@code import module ax.xz.mri;} for {@link EigenfieldScript},
 * {@link ax.xz.mri.model.simulation.Vec3}, and friends).
 */
public final class EigenfieldEngine {
    private EigenfieldEngine() {}

    private static final ScriptEngine<EigenfieldScript> ENGINE =
        new ScriptEngine<>(EigenfieldScript.class, List.of());

    public static EigenfieldScript compile(String source) {
        return ENGINE.compile(source);
    }

    public static EigenfieldScript compileUncached(String source) {
        return ENGINE.compileUncached(source);
    }

    public static void clearCache() {
        ENGINE.clearCache();
    }
}
