package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaDiagnostics;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaDiscovery;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Hosts the five Red Pitaya sub-tabs and owns the live discovery /
 * diagnostics workers. Lifecycle is tied to the JavaFX scene: when the
 * editor's view is added to a scene we start workers, when it leaves we
 * stop them — that pattern survives the host pane being closed without
 * needing a dispose hook on {@link HardwareConfigEditor}.
 */
public final class RedPitayaConfigEditor implements HardwareConfigEditor {

    private RedPitayaConfig current;
    private Runnable onEdited = () -> {};
    private boolean suppressEdits;

    private final TabPane root = new TabPane();
    private final List<ConfigTab> tabs;

    private final RedPitayaDiscovery discovery = new RedPitayaDiscovery();
    private final RedPitayaDiagnostics diagnostics;

    private boolean workersRunning;

    public RedPitayaConfigEditor(RedPitayaConfig initial) {
        this.current = initial;
        this.diagnostics = new RedPitayaDiagnostics(initial);

        var ctx = new EditContext() {
            @Override public RedPitayaConfig current() { return current; }
            @Override public void edit(Function<RedPitayaConfig, RedPitayaConfig> mutator) {
                if (suppressEdits) return;
                var next = mutator.apply(current);
                if (next == null || next.equals(current)) return;
                current = next;
                diagnostics.updateConfig(current);
                onEdited.run();
            }
        };

        var connectionTab = new ConnectionTab(ctx, discovery, diagnostics.snapshot());
        var carrierTab    = new CarrierTab(ctx);
        var mappingTab    = new MappingTab(ctx);
        var diagnosticsTab = new DiagnosticsTab(diagnostics);
        var limitsTab     = new LimitsTab();
        this.tabs = List.of(connectionTab, carrierTab, mappingTab, diagnosticsTab, limitsTab);

        root.getStyleClass().add("cfg-tabs");
        root.getTabs().addAll(
            asTab("Connection", connectionTab),
            asTab("Carrier",    carrierTab),
            asTab("Mapping",    mappingTab),
            asTab("Diagnostics", diagnosticsTab),
            asTab("Limits",     limitsTab)
        );

        root.sceneProperty().addListener((obs, prev, scene) -> {
            if (scene != null) ensureWorkersRunning();
            else stopWorkers();
        });

        // Refresh limits/derived labels for the initial config.
        for (var t : tabs) t.refresh(current);
    }

    @Override public Node view() { return root; }

    @Override
    public void setConfig(HardwareConfig config) {
        if (!(config instanceof RedPitayaConfig rp)) return;
        current = rp;
        suppressEdits = true;
        try {
            for (var t : tabs) t.refresh(current);
            diagnostics.updateConfig(current);
        } finally { suppressEdits = false; }
    }

    @Override public HardwareConfig snapshot() { return current; }
    @Override public void setOnEdited(Runnable listener) { this.onEdited = listener != null ? listener : () -> {}; }

    private synchronized void ensureWorkersRunning() {
        if (workersRunning) return;
        workersRunning = true;
        try { discovery.start(); } catch (IOException ignored) { /* stays empty; user can still type a hostname */ }
        diagnostics.start();
    }

    private synchronized void stopWorkers() {
        if (!workersRunning) return;
        workersRunning = false;
        diagnostics.close();
        discovery.close();
    }

    private static Tab asTab(String title, ConfigTab t) {
        var tab = new Tab(title, t.view());
        tab.setClosable(false);
        return tab;
    }
}
