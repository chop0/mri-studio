package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression tests for the bug where the bound hardware config showed up as
 * "no config bound" in the UI even though the user had selected one.
 *
 * <p>The root cause was an identity-vs-id confusion in
 * {@link SequenceEditSession}: the binding was kept as a
 * {@link HardwareConfigDocument} reference, but every time the document was
 * re-saved, renamed, or in-place edited the in-memory state replaced it with
 * a fresh immutable instance and the cached reference went stale.
 *
 * <p>The fix: bind by {@link ProjectNodeId}, resolve the document through
 * the live state on demand. These tests pin the contract.
 */
class SequenceEditSessionHardwareBindingTest {

    @BeforeAll
    static void refreshRegistry() {
        HardwarePluginRegistry.refresh();
    }

    private static HardwareConfigDocument freshDoc(String name) {
        var plugin = HardwarePluginRegistry.byId(RedPitayaConfig.PLUGIN_ID).orElseThrow();
        return HardwareConfigDocument.of(
            new ProjectNodeId("hwcfg-" + java.util.UUID.randomUUID()),
            name,
            plugin.defaultConfig());
    }

    @Test
    void bindingByIdSurvivesRenameInRepository() {
        var repo = new AtomicReference<>(ProjectState.empty());
        var original = freshDoc("MyRP");
        repo.set(repo.get().withHardware(original));

        var session = new SequenceEditSession();
        session.setRepositorySupplier(repo::get);
        session.activeHardwareConfigId.set(original.id());

        assertSame(original, session.activeHardwareConfigDoc());

        var renamed = original.withName("TheRP");
        repo.set(repo.get().withHardware(renamed));
        assertNotSame(original, renamed, "rename must produce a fresh doc instance");

        var resolved = session.activeHardwareConfigDoc();
        assertNotNull(resolved, "binding must survive rename");
        assertEquals("TheRP", resolved.name());
        assertSame(renamed, resolved);
        assertEquals(original.id(), session.activeHardwareConfigId.get());
    }

    @Test
    void bindingByIdSurvivesInPlaceUpdateInRepository() {
        var repo = new AtomicReference<>(ProjectState.empty());
        var original = freshDoc("MyRP");
        repo.set(repo.get().withHardware(original));

        var session = new SequenceEditSession();
        session.setRepositorySupplier(repo::get);
        session.activeHardwareConfigId.set(original.id());

        var plugin = HardwarePluginRegistry.byId(RedPitayaConfig.PLUGIN_ID).orElseThrow();
        var newCfg = ((RedPitayaConfig) plugin.defaultConfig()).withTxCarrierHz(99.9e6);
        var updated = original.withConfig(newCfg);
        repo.set(repo.get().withHardware(updated));

        var resolved = session.activeHardwareConfigDoc();
        assertNotNull(resolved, "binding must survive an in-place save");
        assertSame(updated, resolved);
        assertEquals(99.9e6, ((RedPitayaConfig) resolved.config()).txCarrierHz());
    }

    @Test
    void bindingResolvesToNullWhenConfigDeleted() {
        var repo = new AtomicReference<>(ProjectState.empty());
        var doc = freshDoc("MyRP");
        repo.set(repo.get().withHardware(doc));

        var session = new SequenceEditSession();
        session.setRepositorySupplier(repo::get);
        session.activeHardwareConfigId.set(doc.id());

        repo.set(repo.get().withoutHardware(doc.id()));

        assertEquals(doc.id(), session.activeHardwareConfigId.get());
        assertNull(session.activeHardwareConfigDoc());
    }

    @Test
    void switchingBindingClearsHardwareTracesAndOutputs() {
        var repo = new AtomicReference<>(ProjectState.empty());
        var docA = freshDoc("A");
        var docB = freshDoc("B");
        repo.set(repo.get().withHardware(docA).withHardware(docB));

        var session = new SequenceEditSession();
        session.setRepositorySupplier(repo::get);
        session.activeHardwareConfigId.set(docA.id());

        assertEquals(java.util.Set.of("rp.rx", "rp.rx.i", "rp.rx.q"), session.enabledHardwareOutputs);
        session.lastHardwareTraces.set(new ax.xz.mri.model.simulation.MultiProbeSignalTrace(
            java.util.Map.of(), null));
        assertNotNull(session.lastHardwareTraces.get());

        session.activeHardwareConfigId.set(docB.id());
        assertNull(session.lastHardwareTraces.get(),
            "switching binding must clear stale device-specific traces");
        assertEquals(java.util.Set.of("rp.rx", "rp.rx.i", "rp.rx.q"), session.enabledHardwareOutputs,
            "probe enables must re-prime for the new device");
    }

    @Test
    void inPlaceEditDoesNotClearTracesOrOutputs() {
        var repo = new AtomicReference<>(ProjectState.empty());
        var doc = freshDoc("MyRP");
        repo.set(repo.get().withHardware(doc));

        var session = new SequenceEditSession();
        session.setRepositorySupplier(repo::get);
        session.activeHardwareConfigId.set(doc.id());

        var sentinel = new ax.xz.mri.model.simulation.MultiProbeSignalTrace(
            java.util.Map.of(), null);
        session.lastHardwareTraces.set(sentinel);

        var plugin = HardwarePluginRegistry.byId(RedPitayaConfig.PLUGIN_ID).orElseThrow();
        var newCfg = ((RedPitayaConfig) plugin.defaultConfig()).withTxGain(0.99);
        repo.set(repo.get().withHardware(doc.withConfig(newCfg)));

        assertSame(sentinel, session.lastHardwareTraces.get(),
            "in-place edit of bound config must not discard hardware traces");
    }
}
