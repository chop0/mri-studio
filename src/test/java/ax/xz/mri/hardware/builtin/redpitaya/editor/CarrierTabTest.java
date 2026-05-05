package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaChannel;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaSampleRate;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaTxPort;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the carrier-tab lock + dirty semantics — both bugs
 * the field reported (RX field not updating on TX edit while locked, and
 * lock toggles dirtying the document inappropriately).
 */
class CarrierTabTest {

    @BeforeAll
    static void initFx() {
        FxTestSupport.startToolkit();
    }

    @Test
    void editingTxWhileLockedAlsoUpdatesRxFieldAndConfig() {
        FxTestSupport.runOnFxThread(() -> {
            var cfg = new AtomicReference<>(defaults());
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));
            assertTrue(tab.lockForTest().isSelected(), "lock should start ON when tx == rx");

            tab.txFieldForTest().setValue(15.0e6);
            assertEquals(15.0e6, tab.rxFieldForTest().getValue(), 1e-9, "RX field display should follow TX");
            assertEquals(15.0e6, cfg.get().txCarrierHz(), 1e-9);
            assertEquals(15.0e6, cfg.get().rxCarrierHz(), 1e-9);
            assertEquals(1, edits.get(), "exactly one edit, not two");
        });
    }

    @Test
    void editingRxWhileLockedAlsoUpdatesTxFieldAndConfig() {
        FxTestSupport.runOnFxThread(() -> {
            var cfg = new AtomicReference<>(defaults());
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));

            tab.rxFieldForTest().setValue(11.0e6);
            assertEquals(11.0e6, tab.txFieldForTest().getValue(), 1e-9, "TX field display should follow RX");
            assertEquals(11.0e6, cfg.get().txCarrierHz(), 1e-9);
            assertEquals(11.0e6, cfg.get().rxCarrierHz(), 1e-9);
            assertEquals(1, edits.get());
        });
    }

    @Test
    void editingTxWhileUnlockedDoesNotTouchRx() {
        FxTestSupport.runOnFxThread(() -> {
            var cfg = new AtomicReference<>(defaults());
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));

            tab.lockForTest().setSelected(false);
            int before = edits.get();
            tab.txFieldForTest().setValue(7.5e6);

            assertEquals(7.5e6, cfg.get().txCarrierHz(), 1e-9);
            assertEquals(21.3e6, cfg.get().rxCarrierHz(), 1e-9, "RX must not move when unlocked");
            assertEquals(before + 1, edits.get());
        });
    }

    @Test
    void togglingLockOffDoesNotDirtyTheDocument() {
        FxTestSupport.runOnFxThread(() -> {
            var cfg = new AtomicReference<>(defaults());
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));

            int before = edits.get();
            tab.lockForTest().setSelected(false);
            assertEquals(before, edits.get(),
                "unchecking the lock changes no config field — it must not dirty the document");
        });
    }

    @Test
    void togglingLockOnSnapsRxToTxAndDirtiesOnce() {
        FxTestSupport.runOnFxThread(() -> {
            // Start unlocked with TX != RX
            var cfg = new AtomicReference<>(defaults().withRxCarrierHz(19.0e6));
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));
            assertFalse(tab.lockForTest().isSelected(), "lock should start OFF when tx != rx");

            int before = edits.get();
            tab.lockForTest().setSelected(true);

            assertEquals(21.3e6, cfg.get().rxCarrierHz(), 1e-9, "RX should snap to TX on lock-on");
            assertEquals(21.3e6, tab.rxFieldForTest().getValue(), 1e-9);
            assertEquals(before + 1, edits.get());
        });
    }

    @Test
    void refreshFromOutsideDoesNotFireEdits() {
        FxTestSupport.runOnFxThread(() -> {
            var cfg = new AtomicReference<>(defaults());
            var edits = new AtomicInteger();
            var tab = new CarrierTab(ctx(cfg, edits));

            int before = edits.get();
            tab.refresh(defaults().withTxCarrierHz(5e6).withRxCarrierHz(5e6));
            assertEquals(before, edits.get(), "external setConfig must not fire edit listeners");
            assertEquals(5e6, tab.txFieldForTest().getValue(), 1e-9);
            assertEquals(5e6, tab.rxFieldForTest().getValue(), 1e-9);
        });
    }

    // --- helpers ------------------------------------------------------------

    private static RedPitayaConfig defaults() {
        return new RedPitayaConfig(
            "rp-XXXXXX.local", 6981, 2000,
            21.3e6, 21.3e6,
            RedPitayaSampleRate.DECIM_8,
            RedPitayaTxPort.OUT1,
            0.5,
            (RedPitayaChannel) null,
            Map.<RedPitayaChannel, String>of(),
            3000);
    }

    /** Tracks the current config and counts edit invocations. */
    private static EditContext ctx(AtomicReference<RedPitayaConfig> ref, AtomicInteger counter) {
        return new EditContext() {
            @Override public RedPitayaConfig current() { return ref.get(); }
            @Override public void edit(Function<RedPitayaConfig, RedPitayaConfig> mutator) {
                var next = mutator.apply(ref.get());
                if (next == null || next.equals(ref.get())) return;
                ref.set(next);
                counter.incrementAndGet();
            }
        };
    }
}
