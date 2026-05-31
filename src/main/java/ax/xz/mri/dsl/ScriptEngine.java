package ax.xz.mri.dsl;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process JDK-{@code javac}-backed compiler for user-authored DSL scripts.
 *
 * <p>One engine instance per script kind. The engine is parameterised on the
 * {@link S} interface the user implements ({@link EigenfieldScript}, the
 * forthcoming procedure kinds, etc.). The user writes a complete Java
 * compilation unit — typically a single class in the default package — and
 * pulls in whatever they need with regular imports:
 *
 * <pre>{@code
 * import module ax.xz.mri;       // Java 25 module import — brings in Vec3,
 *                                // EigenfieldScript, MagnetisationState…
 * import static java.lang.Math.*;
 *
 * class HelmholtzB0 implements EigenfieldScript {
 *     public Vec3 evaluate(double x, double y, double z) { … }
 *     static double peakAtIsocentre(double d, double R) { … }
 * }
 * }</pre>
 *
 * <p>No engine-side import magic — what the user writes is what gets compiled.
 * The JDK compiler handles records, sealed types, switch patterns, module
 * imports, every modern Java feature; Janino is gone.
 *
 * <p>A compiled-script cache keys on the {@code source} string so repeated
 * compile calls on the same source share an instance.
 */
public final class ScriptEngine<S> {

    private static final int CACHE_LIMIT = 64;
    private static final Pattern CLASS_DECL = Pattern.compile(
        "(?:^|\\n)\\s*(?:public\\s+|abstract\\s+|final\\s+)*class\\s+([A-Za-z_$][A-Za-z_$0-9]*)");

    private final Class<S> iface;
    private final List<String> javacOptions;
    private final Map<String, S> cache = synchronizedLruCache();

    /**
     * Eagerly load {@code jdk.compiler} so the first user compile inside the
     * editor doesn't pay the ~hundreds-of-ms cold-start cost. Side effect of
     * loading the class; harmless if invoked from multiple engines.
     */
    static {
        ToolProvider.getSystemJavaCompiler();
    }

    /**
     * Build an engine that compiles user sources against the supplied
     * interface. {@code javacOptions} can carry release flags, classpath
     * pointers, or {@code --enable-preview} when a preview feature is needed;
     * pass an empty list for the default (current runtime release, no preview).
     */
    public ScriptEngine(Class<S> iface, List<String> javacOptions) {
        this.iface = iface;
        this.javacOptions = List.copyOf(javacOptions);
    }

    public ScriptEngine(Class<S> iface) {
        this(iface, List.of());
    }

    /**
     * Compile {@code source} into an instance of {@link S}.
     *
     * @throws ScriptCompileException if the source has no class declaration,
     *         fails {@code javac}'s compile, or the discovered class doesn't
     *         implement {@link S}.
     */
    public S compile(String source) {
        if (source == null || source.isBlank()) {
            throw new ScriptCompileException("Script is empty.", 1, 1, null);
        }
        var cached = cache.get(source);
        if (cached != null) return cached;

        var compiled = compileUncached(source);
        cache.put(source, compiled);
        return compiled;
    }

    /** Bypass the cache and recompile. */
    public S compileUncached(String source) {
        var className = extractClassName(source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ScriptCompileException(
                "No system Java compiler available — the studio must run on a JDK, not a JRE.",
                0, 0, null);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var memOut = new HashMap<String, ByteArrayOutputStream>();
        try (var std = compiler.getStandardFileManager(diagnostics, null, null)) {
            configureModulePath(compiler, std);
            var fileManager = new InMemoryFileManager(std, memOut);
            var unit = new InMemorySource(className, source);
            var task = compiler.getTask(null, fileManager, diagnostics,
                buildJavacOptions(), null, List.of(unit));
            boolean ok = task.call();
            if (!ok) {
                throw firstError(diagnostics);
            }
            var loader = new InMemoryClassLoader(memOut, ScriptEngine.class.getClassLoader());
            var cls = Class.forName(className, true, loader);
            if (!iface.isAssignableFrom(cls)) {
                throw new ScriptCompileException(
                    "class " + className + " does not implement " + iface.getSimpleName(),
                    1, 1, null);
            }
            // The user's script is in the default package with no explicit
            // modifiers, so its no-arg constructor is package-private. The
            // engine module can't see that package, so reflective access
            // must be opened explicitly before newInstance().
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            @SuppressWarnings("unchecked")
            S instance = (S) ctor.newInstance();
            return instance;
        } catch (ScriptCompileException sce) {
            throw sce;
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            throw new ScriptCompileException(msg, 0, 0, t);
        }
    }

    /**
     * Point the standard file manager's {@code MODULE_PATH} at everywhere the
     * runtime might have a named module. Two sources:
     *
     * <ol>
     *   <li>The boot module layer — every named module loaded as JPMS
     *       (project module, JDK modules, third-party modular jars on the
     *       modulepath).</li>
     *   <li>{@code java.class.path} — modular jars and modular classes
     *       directories Gradle put on the test runtime classpath. javac
     *       reads {@code module-info.class} from inside each entry and
     *       resolves those as named modules even though the running JVM
     *       sees them as the unnamed module.</li>
     * </ol>
     *
     * <p>Without (2), {@code import module ax.xz.mri;} fails inside the
     * test JVM because Gradle puts the project jar on the classpath rather
     * than the modulepath when test sources have no {@code module-info}.
     */
    private static void configureModulePath(JavaCompiler compiler, javax.tools.StandardJavaFileManager std) {
        var paths = new LinkedHashSet<Path>();
        for (var m : ModuleLayer.boot().modules()) {
            m.getLayer().configuration().findModule(m.getName())
                .flatMap(ref -> ref.reference().location())
                .filter(uri -> "file".equals(uri.getScheme()))
                .map(Path::of)
                .ifPresent(paths::add);
        }
        var classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            for (var entry : classPath.split(File.pathSeparator)) {
                if (entry.isBlank()) continue;
                paths.add(Path.of(entry));
            }
        }
        if (paths.isEmpty()) return;
        try {
            std.setLocationFromPaths(StandardLocation.MODULE_PATH, paths);
        } catch (IOException ignored) {
            // Falls back to default javac search; user must add their own --module-path.
        }
    }

    /**
     * Compose the final javac options. The user can override via the
     * constructor; we always add {@code --add-modules=ALL-MODULE-PATH} so
     * {@code import module} resolves against the runtime modulepath.
     */
    private List<String> buildJavacOptions() {
        var out = new ArrayList<>(javacOptions);
        if (!out.contains("--add-modules")) {
            out.add("--add-modules");
            out.add("ALL-MODULE-PATH");
        }
        return out;
    }

    public void clearCache() { cache.clear(); }

    private ScriptCompileException firstError(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (var d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            int line = (int) d.getLineNumber();
            int col = (int) d.getColumnNumber();
            return new ScriptCompileException(
                d.getMessage(null), Math.max(line, 0), Math.max(col, 0), null);
        }
        return new ScriptCompileException("javac reported failure with no diagnostics.", 0, 0, null);
    }

    /** Find the first top-level class declared in {@code source}. */
    private static String extractClassName(String source) {
        Matcher m = CLASS_DECL.matcher(source);
        if (m.find()) return m.group(1);
        throw new ScriptCompileException(
            "Script must declare a class implementing the script interface.", 1, 1, null);
    }

    private static <K, V> Map<K, V> synchronizedLruCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CACHE_LIMIT;
            }
        });
    }

    /** In-memory {@link JavaFileObject} for a single user source string. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        InMemorySource(String className, String source) {
            super(URI.create("mem:///" + className + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }

    /** In-memory {@link JavaFileObject} that captures compiled class bytes. */
    private static final class InMemoryClass extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes;

        InMemoryClass(String className, ByteArrayOutputStream bytes) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
            this.bytes = bytes;
        }

        @Override public OutputStream openOutputStream() { return bytes; }
    }

    /** Routes output {@code .class} files into an in-memory map. */
    private static final class InMemoryFileManager
        extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<String, ByteArrayOutputStream> output;

        InMemoryFileManager(JavaFileManager delegate, Map<String, ByteArrayOutputStream> output) {
            super(delegate);
            this.output = output;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling)
            throws IOException {
            if (kind != JavaFileObject.Kind.CLASS) {
                return super.getJavaFileForOutput(location, className, kind, sibling);
            }
            return new InMemoryClass(className, output.computeIfAbsent(className, n -> new ByteArrayOutputStream()));
        }
    }

    /** Class loader backed by the in-memory bytecode map. */
    private static final class InMemoryClassLoader extends ClassLoader {
        private final Map<String, ByteArrayOutputStream> classes;

        InMemoryClassLoader(Map<String, ByteArrayOutputStream> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var baos = classes.get(name);
            if (baos == null) throw new ClassNotFoundException(name);
            byte[] data = baos.toByteArray();
            return defineClass(name, data, 0, data.length);
        }
    }

}
