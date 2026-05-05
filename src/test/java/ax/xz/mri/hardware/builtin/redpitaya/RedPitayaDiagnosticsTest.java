package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.DeviceFact;
import ax.xz.mri.hardware.builtin.redpitaya.proto.DiagReply;
import ax.xz.mri.hardware.builtin.redpitaya.proto.HelloAck;
import ax.xz.mri.hardware.builtin.redpitaya.proto.ParamValue;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedPitayaDiagnosticsTest {

    @BeforeAll
    static void initFx() {
        FxTestSupport.startToolkit();
    }

    @Test
    void unreachableHostProducesUnreachableSnapshot() throws Exception {
        // Use a port nothing listens on; the 200 ms TCP probe times out → unreachable.
        var cfg = new RedPitayaConfig("127.0.0.1", 1, 200, 21.3e6, 21.3e6,
            RedPitayaSampleRate.DECIM_8, RedPitayaTxPort.OUT1, 0.5, null,
            java.util.Map.of(), 1000);
        try (var diag = new RedPitayaDiagnostics(cfg, c -> { throw new IOException("never reached"); })) {
            var seen = new AtomicReference<DiagnosticsSnapshot>();
            var latch = new CountDownLatch(1);
            diag.snapshot().addListener((o, p, n) -> {
                if (n != null && n.pollTime().toEpochMilli() != 0) {
                    seen.set(n);
                    latch.countDown();
                }
            });
            diag.refresh();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "diagnostics never produced a snapshot");
            assertEquals(false, seen.get().reachable());
        }
    }

    @Test
    void reachableServerSnapshotPopulatesTypedFields() throws Exception {
        var server = new java.net.ServerSocket(0);   // local TCP probe target so reachability check passes
        int port = server.getLocalPort();
        new Thread(() -> {
            try {
                while (!server.isClosed()) server.accept().close();
            } catch (IOException ignored) {}
        }, "diag-probe-accept").start();

        var cfg = new RedPitayaConfig("127.0.0.1", port, 500, 21.3e6, 21.3e6,
            RedPitayaSampleRate.DECIM_8, RedPitayaTxPort.OUT1, 0.5, null,
            java.util.Map.of(), 1000);

        var fake = new ScriptedTransport();
        fake.queue(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR)
            .setGitSha("abcdef0").build()).build());
        fake.queue(Reply.newBuilder().setDiagReply(DiagReply.newBuilder()
            .addFacts(deviceFact("model", "STEMlab 125-14"))
            .addFacts(deviceFact("mac", "00:26:32:f0:3e:18"))
            .addFacts(deviceFact("kernel", "Linux 6.1.0"))
            .addFacts(deviceFact("uptime_s", 3661L))
            .addFacts(deviceFact("cpu_temp_c", 47.5))
            .addFacts(deviceFact("link_speed_mbps", 1000L))
            .addFacts(deviceFact("adc_overflows_since_boot", 0L))
            .addFacts(deviceFact("dac_underruns_since_boot", 0L))
            .addFacts(deviceFact("future_field", "ignore-me-gracefully"))
            .build()).build());

        try (var diag = new RedPitayaDiagnostics(cfg, c -> fake)) {
            var seen = new AtomicReference<DiagnosticsSnapshot>();
            var latch = new CountDownLatch(1);
            diag.snapshot().addListener((o, p, n) -> {
                if (n != null && n.reachable()) {
                    seen.set(n);
                    latch.countDown();
                }
            });
            diag.refresh();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "diagnostics never produced a reachable snapshot");
            var snap = seen.get();
            assertEquals("STEMlab 125-14", snap.model());
            assertEquals("00:26:32:f0:3e:18", snap.mac());
            assertEquals("Linux 6.1.0", snap.kernel());
            assertEquals(3661L, snap.uptimeSeconds());
            assertEquals(47.5, snap.cpuTempCelsius(), 1e-9);
            assertEquals(1000L, snap.linkSpeedMbps());
            assertEquals("abcdef0", snap.serverGitSha());
            assertNotNull(snap.extras().get("future_field"));
        } finally {
            server.close();
        }
    }

    private static DeviceFact deviceFact(String key, String value) {
        return DeviceFact.newBuilder().setKey(key).setValue(ParamValue.newBuilder().setS(value).build()).build();
    }

    private static DeviceFact deviceFact(String key, long value) {
        return DeviceFact.newBuilder().setKey(key).setValue(ParamValue.newBuilder().setI(value).build()).build();
    }

    private static DeviceFact deviceFact(String key, double value) {
        return DeviceFact.newBuilder().setKey(key).setValue(ParamValue.newBuilder().setD(value).build()).build();
    }

    private static final class ScriptedTransport implements RedPitayaTransport {
        private final Deque<Reply> q = new ArrayDeque<>();

        void queue(Reply r) { q.add(r); }

        @Override public void send(Command command) {}
        @Override public Reply receive() {
            var r = q.poll();
            if (r == null) throw new IllegalStateException("no scripted reply");
            return r;
        }
        @Override public void close() {}
    }
}
