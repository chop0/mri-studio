package ax.xz.mri.model.simulation.dsl;

import ax.xz.mri.model.simulation.Vec3;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ScriptEvaluator;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles eigenfield DSL source into a callable {@link EigenfieldScript}.
 *
 * <p>The DSL is pure Java — users write the body of a method with signature
 * <pre>{@code Vec3 evaluate(double x, double y, double z)}</pre>
 * where {@code (x, y, z)} are in metres. All of {@link Math} is star-imported
 * so {@code sin}, {@code cos}, {@code sqrt}, {@code PI}, etc. are available
 * without qualification. {@link Vec3} is imported for the return type.
 *
 * <p>Compiled scripts are cached by source string. Compilation is ~1-10 ms
 * on a warm JVM, fast enough for live-preview recompilation on keystroke.
 */
public final class EigenfieldScriptEngine {
    private static final int CACHE_LIMIT = 64;

    private static final Map<String, EigenfieldScript> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, EigenfieldScript> eldest) {
                return size() > CACHE_LIMIT;
            }
        });

    private EigenfieldScriptEngine() {}

    /**
     * Compile {@code source} into an executable script.
     *
     * @throws ScriptCompileException if the source is not valid (syntax, type,
     *         unresolved symbol).
     */
    public static EigenfieldScript compile(String source) {
        if (source == null || source.isBlank()) {
            throw new ScriptCompileException("Script is empty.", 1, 1, null);
        }
        var cached = CACHE.get(source);
        if (cached != null) return cached;

        var compiled = compileUncached(source);
        CACHE.put(source, compiled);
        return compiled;
    }

    /** Bypass the cache and recompile. Useful for benchmarking. */
    public static EigenfieldScript compileUncached(String source) {
        final ScriptEvaluator evaluator;
        try {
            evaluator = new ScriptEvaluator();
            evaluator.setParentClassLoader(EigenfieldScriptEngine.class.getClassLoader());
            evaluator.setReturnType(Vec3.class);
            evaluator.setParameters(
                new String[]{"x", "y", "z"},
                new Class<?>[]{double.class, double.class, double.class}
            );
            evaluator.setDefaultImports(
                "static java.lang.Math.*",
                "ax.xz.mri.model.simulation.Vec3"
            );
            evaluator.cook(source);
        } catch (CompileException ce) {
            var loc = ce.getLocation();
            int line = loc != null ? loc.getLineNumber() : 0;
            int col = loc != null ? loc.getColumnNumber() : 0;
            throw new ScriptCompileException(ce.getMessage(), line, col, ce);
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            throw new ScriptCompileException(msg, 0, 0, t);
        }

        return (x, y, z) -> {
            try {
                var result = evaluator.evaluate(new Object[]{x, y, z});
                return result instanceof Vec3 vec3 ? vec3 : Vec3.ZERO;
            } catch (InvocationTargetException ite) {
                throw new ScriptRuntimeException(ite.getCause() != null ? ite.getCause() : ite);
            } catch (Throwable t) {
                throw new ScriptRuntimeException(t);
            }
        };
    }

    /** Wraps script-side runtime errors so callers can distinguish them from compile errors. */
    public static final class ScriptRuntimeException extends RuntimeException {
        public ScriptRuntimeException(Throwable cause) { super(cause); }
    }

    /** Clear the compiled-script cache. Exposed for tests. */
    public static void clearCache() {
        CACHE.clear();
    }
}
