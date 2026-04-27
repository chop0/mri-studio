package ax.xz.mri.hardware;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link HardwarePlugin} implementations via Java's
 * {@link ServiceLoader} mechanism.
 *
 * <p>Built-in plugins ship in the main module
 * (declared via {@code provides ax.xz.mri.hardware.HardwarePlugin with ...}
 * in {@code module-info.java}). Future external plugins can be loaded by
 * adding additional provider declarations in their own JARs — no change
 * to this registry is required.
 *
 * <p>Discovery happens lazily on first call and caches the result for the
 * lifetime of the JVM. {@link #refresh()} rescans, useful in tests that
 * stub plugins through the system class loader.
 */
public final class HardwarePluginRegistry {
    private HardwarePluginRegistry() {}

    private static volatile List<HardwarePlugin> cached;

    /** All plugins discovered on the classpath/modulepath, in load order. */
    public static synchronized List<HardwarePlugin> all() {
        if (cached == null) cached = loadAll();
        return cached;
    }

    /** Look up a plugin by {@link HardwarePlugin#id()}. */
    public static Optional<HardwarePlugin> byId(String id) {
        if (id == null) return Optional.empty();
        return all().stream().filter(p -> id.equals(p.id())).findFirst();
    }

    /** Force a rescan of providers. Mainly for tests. */
    public static synchronized void refresh() {
        cached = loadAll();
    }

    private static List<HardwarePlugin> loadAll() {
        return ServiceLoader.load(HardwarePlugin.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();
    }
}
