package ax.xz.mri.hardware.builtin.redpitaya;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mDNS discovery of Red Pitaya devices via the OS-native mDNS browser.
 *
 * <p>JmDNS turned out to silently miss Red Pitayas reachable through a
 * bridge interface (notably macOS Internet Sharing — the OS resolver works,
 * but {@code JmDNS} bound to the bridge address never receives the response
 * multicast). The OS-native browsers don't have that problem, so we shell
 * out to them: {@code dns-sd} on macOS, {@code avahi-browse} on Linux.
 *
 * <p>The class is shaped like a JavaFX-friendly observable: a single
 * {@link ObservableList} of detected hosts plus {@link #start()} /
 * {@link #close()}. Mutations are marshalled onto the FX thread.
 */
public final class RedPitayaDiscovery implements AutoCloseable {

    private static final Pattern RP_HOSTNAME = Pattern.compile("rp-[0-9a-fA-F]+\\.local\\.?");
    /**
     * Service types Red Pitaya stock OS advertises. Browsing any one of
     * them is enough to find the device — we use {@code _ssh._tcp} since
     * it's the most universal.
     */
    private static final String SERVICE_TYPE = "_ssh._tcp";

    public record Detected(String hostname, String ip, Instant lastSeen) {}

    private final ObservableList<Detected> hosts = FXCollections.observableArrayList();
    private final Map<String, Detected> byHost = new LinkedHashMap<>();
    private final List<Backend> backends = new ArrayList<>();
    private boolean started;
    private boolean closed;

    public ObservableList<Detected> hosts() {
        return hosts;
    }

    public synchronized void start() throws IOException {
        if (started || closed) return;
        started = true;
        for (Backend b : pickBackends()) {
            b.start(this::announce);
            backends.add(b);
        }
        if (backends.isEmpty()) {
            throw new IOException(
                "No mDNS backend available — install Bonjour (macOS) or avahi-utils (Linux).");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Backend b : backends) b.close();
        backends.clear();
    }

    /** Called from a backend thread; marshals to the FX thread. */
    private void announce(Detected d) {
        Platform.runLater(() -> {
            Detected prev = byHost.put(d.hostname(), d);
            if (prev == null) hosts.add(d);
            else hosts.set(hosts.indexOf(prev), d);
        });
    }

    private static List<Backend> pickBackends() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac"))   return List.of(new DnsSdBackend());
        if (os.contains("linux")) return List.of(new AvahiBrowseBackend());
        return List.of();   // No fallback — manual hostname entry still works.
    }

    // ---- Backend abstraction -----------------------------------------------

    private interface Backend {
        void start(java.util.function.Consumer<Detected> sink) throws IOException;
        void close();
    }

    /**
     * Macros for both backends:
     *   1. start a long-lived browser process that prints one line per service add
     *   2. for each Add, start a one-shot resolver to get the hostname
     *   3. if the hostname matches {@code rp-XXXXXX.local}, emit it
     *
     * Both processes are killed on {@link #close()}.
     */
    private static abstract class SubprocessBackend implements Backend {
        private Process browser;
        private Thread reader;
        private final AtomicReference<java.util.function.Consumer<Detected>> sinkRef = new AtomicReference<>();

        @Override
        public void start(java.util.function.Consumer<Detected> sink) throws IOException {
            sinkRef.set(sink);
            browser = browserCommand().redirectErrorStream(true).start();
            reader = new Thread(() -> readBrowserOutput(browser), "redpitaya-mdns-" + getClass().getSimpleName());
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void close() {
            if (browser != null) browser.destroy();
        }

        protected abstract ProcessBuilder browserCommand();
        protected abstract String parseInstanceFromBrowserLine(String line);
        protected abstract ProcessBuilder resolveCommand(String instance);
        protected abstract Detected parseResolveOutput(Process resolver, Instant seenAt) throws IOException;

        private void readBrowserOutput(Process p) {
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String instance = parseInstanceFromBrowserLine(line);
                    if (instance != null) resolveAndEmit(instance);
                }
            } catch (IOException ignored) {}
        }

        private void resolveAndEmit(String instance) {
            try {
                Process res = resolveCommand(instance).redirectErrorStream(true).start();
                Detected d = parseResolveOutput(res, Instant.now());
                res.destroy();
                if (d != null && RP_HOSTNAME.matcher(d.hostname() + ".").matches()) {
                    sinkRef.get().accept(d);
                }
            } catch (IOException ignored) {}
        }
    }

    /**
     * macOS: {@code dns-sd -B _ssh._tcp local.} for browsing,
     * {@code dns-sd -L} for resolving. Names with spaces appear as
     * {@code "rp-f03e18 SSH"} in the browse output and as
     * {@code rp-f03e18\032SSH} after resolve — we use the original
     * (un-escaped) form when invoking resolve.
     */
    private static final class DnsSdBackend extends SubprocessBackend {
        private static final Pattern RESOLVED_LINE =
            Pattern.compile("can be reached at\\s+(\\S+?):\\d+");

        @Override protected ProcessBuilder browserCommand() {
            return new ProcessBuilder("dns-sd", "-B", SERVICE_TYPE, "local.");
        }

        @Override protected String parseInstanceFromBrowserLine(String line) {
            // Browse line: "<ts>  Add  <flags> <if> local.  _ssh._tcp.  <Instance Name>"
            // Anything before "_ssh._tcp." is timestamp + flags + interface; after is the name.
            int marker = line.indexOf(SERVICE_TYPE + ".");
            if (marker < 0 || !line.contains(" Add ")) return null;
            String tail = line.substring(marker + (SERVICE_TYPE + ".").length()).trim();
            return tail.isEmpty() ? null : tail;
        }

        @Override protected ProcessBuilder resolveCommand(String instance) {
            return new ProcessBuilder("dns-sd", "-L", instance, SERVICE_TYPE, "local.");
        }

        @Override protected Detected parseResolveOutput(Process resolver, Instant seenAt) throws IOException {
            try (var r = new BufferedReader(new InputStreamReader(resolver.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long deadline = System.currentTimeMillis() + 1500;
                while ((line = r.readLine()) != null) {
                    Matcher m = RESOLVED_LINE.matcher(line);
                    if (m.find()) {
                        String host = m.group(1);
                        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                        return new Detected(host, "", seenAt);
                    }
                    if (System.currentTimeMillis() > deadline) break;
                }
            }
            return null;
        }
    }

    /**
     * Linux: {@code avahi-browse -trp _ssh._tcp} prints one parseable line
     * per resolved service in the form
     * {@code =;eth0;IPv4;rp-f03e18\ SSH;_ssh._tcp;local;rp-f03e18.local;192.168.2.2;22;""}.
     * We use the parseable mode so no separate resolve step is needed.
     */
    private static final class AvahiBrowseBackend implements Backend {
        private Process browser;
        private Thread reader;

        @Override
        public void start(java.util.function.Consumer<Detected> sink) throws IOException {
            browser = new ProcessBuilder("avahi-browse", "-trp", SERVICE_TYPE).start();
            reader = new Thread(() -> {
                try (var r = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.startsWith("=;")) continue;
                        String[] parts = line.split(";");
                        if (parts.length < 8) continue;
                        String host = parts[6];
                        String ip   = parts[7];
                        if (host == null || host.isEmpty()) continue;
                        if (!RP_HOSTNAME.matcher(host + ".").matches()) continue;
                        sink.accept(new Detected(host, ip, Instant.now()));
                    }
                } catch (IOException ignored) {}
            }, "redpitaya-mdns-avahi");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void close() {
            if (browser != null) browser.destroy();
        }
    }
}
