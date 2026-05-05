package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.GetDiag;
import ax.xz.mri.hardware.builtin.redpitaya.proto.HelloAck;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Hello;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Periodic device-info poller for the Diagnostics tab. Each tick:
 * <ol>
 *   <li>200 ms TCP probe to the configured host:port; records reachability + RTT</li>
 *   <li>If reachable, opens a transport, sends {@code Hello} + {@code GetDiag}, builds a {@link DiagnosticsSnapshot}</li>
 *   <li>Posts the snapshot to {@link #snapshot()} on the FX thread</li>
 * </ol>
 *
 * <p>Holds its own short-lived transport per poll. The on-RP server services
 * one client at a time; if a run starts mid-poll the diagnostics poll fails
 * for that tick and the {@link RedPitayaDevice} retries connect briefly.
 *
 * <p>The transport factory is injectable so unit tests can drive the poller
 * with a fake transport instead of a real socket.
 */
public final class RedPitayaDiagnostics implements AutoCloseable {

    @FunctionalInterface
    public interface TransportFactory {
        RedPitayaTransport open(RedPitayaConfig config) throws IOException;
    }

    private final ObjectProperty<DiagnosticsSnapshot> snapshot =
        new SimpleObjectProperty<>(DiagnosticsSnapshot.unreachable(Instant.EPOCH, Double.NaN));
    private final ScheduledExecutorService scheduler;
    private final TransportFactory transportFactory;
    private final AtomicReference<RedPitayaConfig> config;
    private ScheduledFuture<?> task;
    private boolean closed;

    public RedPitayaDiagnostics(RedPitayaConfig initial) {
        this(initial, RedPitayaDiagnostics::openTcp);
    }

    public RedPitayaDiagnostics(RedPitayaConfig initial, TransportFactory transportFactory) {
        this.config = new AtomicReference<>(initial);
        this.transportFactory = transportFactory;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "redpitaya-diagnostics");
            t.setDaemon(true);
            return t;
        });
    }

    public ObjectProperty<DiagnosticsSnapshot> snapshot() {
        return snapshot;
    }

    public synchronized void start() {
        if (closed || task != null) return;
        long periodMs = Math.max(500, config.get().diagnosticPollMs());
        task = scheduler.scheduleAtFixedRate(this::poll, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public void updateConfig(RedPitayaConfig newConfig) {
        var old = this.config.getAndSet(newConfig);
        if (task != null && old.diagnosticPollMs() != newConfig.diagnosticPollMs()) {
            stop();
            start();
        }
    }

    /** Force one poll on the scheduler thread; useful for the Refresh button. */
    public void refresh() {
        scheduler.execute(this::poll);
    }

    @Override
    public synchronized void close() {
        closed = true;
        stop();
        scheduler.shutdownNow();
    }

    private void poll() {
        if (closed) return;
        var cfg = config.get();
        var pollStart = Instant.now();
        long t0 = System.nanoTime();
        DiagnosticsSnapshot snap;
        try (var probe = new Socket()) {
            probe.connect(new InetSocketAddress(cfg.hostname(), cfg.port()), 200);
        } catch (IOException ex) {
            snap = DiagnosticsSnapshot.unreachable(pollStart, msSince(t0));
            postSnapshot(snap);
            return;
        }

        try (var transport = transportFactory.open(cfg)) {
            transport.send(Command.newBuilder().setHello(Hello.newBuilder()
                .setClientProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR)
                .setClientProtoMinor(RedPitayaPlugin.CLIENT_PROTO_MINOR)
                .build()).build());
            Reply hello = transport.receive();
            String gitSha = hello.getBodyCase() == Reply.BodyCase.HELLO_ACK ? hello.getHelloAck().getGitSha() : "";
            transport.send(Command.newBuilder().setGetDiag(GetDiag.getDefaultInstance()).build());
            Reply diag = transport.receive();
            if (diag.getBodyCase() != Reply.BodyCase.DIAG_REPLY) {
                snap = DiagnosticsSnapshot.unreachable(pollStart, msSince(t0));
            } else {
                snap = DiagnosticsSnapshot.from(pollStart, msSince(t0), diag.getDiagReply(), gitSha);
            }
        } catch (IOException ex) {
            snap = DiagnosticsSnapshot.unreachable(pollStart, msSince(t0));
        }
        postSnapshot(snap);
    }

    private void postSnapshot(DiagnosticsSnapshot snap) {
        if (Platform.isFxApplicationThread()) snapshot.set(snap);
        else Platform.runLater(() -> snapshot.set(snap));
    }

    private static double msSince(long t0Nanos) {
        return (System.nanoTime() - t0Nanos) / 1_000_000.0;
    }

    private static RedPitayaTransport openTcp(RedPitayaConfig cfg) throws IOException {
        return new TcpRedPitayaTransport(cfg.hostname(), cfg.port(), cfg.connectTimeoutMs());
    }
}
