package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live mDNS smoke test. Boots a {@link RedPitayaDiscovery} and waits for
 * the configured Red Pitaya to appear in the observable host list.
 *
 * <p>Skipped by default; enable with
 * <pre>./gradlew test -Dredpitaya.smoke.host=rp-f03e18.local</pre>
 *
 * <p>Failure here usually means one of:
 * <ul>
 *   <li>The RP isn't on the same L2 segment (mDNS is link-local).</li>
 *   <li>Host firewall is dropping inbound multicast on UDP/5353.</li>
 *   <li>JmDNS bound to the wrong interface
 *       — pin the right one via {@code -Djava.rmi.server.hostname} or
 *       extend {@link RedPitayaDiscovery} to take an interface.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "redpitaya.smoke.host", matches = ".+")
class RedPitayaDiscoveryLiveTest {

    @BeforeAll
    static void initFx() {
        FxTestSupport.startToolkit();
    }

    @Test
    void discoversConfiguredRedPitayaWithinTimeout() throws Exception {
        String expected = System.getProperty("redpitaya.smoke.host");
        try (var discovery = new RedPitayaDiscovery()) {
            discovery.start();

            var found = new CountDownLatch(1);
            discovery.hosts().addListener((javafx.collections.ListChangeListener<RedPitayaDiscovery.Detected>) c -> {
                while (c.next()) {
                    for (var added : c.getAddedSubList()) {
                        System.out.println("[discovery] " + added.hostname() + " @ " + added.ip());
                        if (added.hostname().equalsIgnoreCase(expected)) found.countDown();
                    }
                }
            });

            // Final state may already include the host — the listener wouldn't have seen it.
            FxTestSupport.runOnFxThread(() -> {
                for (var h : discovery.hosts()) {
                    if (h.hostname().equalsIgnoreCase(expected)) found.countDown();
                }
            });

            if (!found.await(15, TimeUnit.SECONDS)) {
                StringBuilder seen = new StringBuilder();
                FxTestSupport.runOnFxThread(() -> {
                    for (var h : discovery.hosts()) seen.append("\n  ").append(h);
                });
                fail("Did not see " + expected + " within 15 s. Hosts seen:" + seen);
            }
            assertTrue(true);
        }
    }
}
