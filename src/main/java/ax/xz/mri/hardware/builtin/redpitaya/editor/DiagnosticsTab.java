package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.DiagnosticsSnapshot;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaDiagnostics;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;

/**
 * Live device diagnostics. Reads from a {@link RedPitayaDiagnostics} the
 * editor owns and shows model / MAC / kernel / uptime / CPU temp / link
 * speed / counters. Refresh button forces an out-of-cycle poll.
 */
final class DiagnosticsTab implements ConfigTab {

    private final RedPitayaDiagnostics diagnostics;
    private final VBox root = new VBox(10);

    private final Label modelLabel    = new Label("--");
    private final Label macLabel      = new Label("--");
    private final Label kernelLabel   = new Label("--");
    private final Label uptimeLabel   = new Label("--");
    private final Label cpuTempLabel  = new Label("--");
    private final Label linkLabel     = new Label("--");
    private final Label adcOverLabel  = new Label("--");
    private final Label dacUnderLabel = new Label("--");
    private final Label lastPollLabel = new Label("never");

    private final VBox extrasBox = new VBox(2);

    DiagnosticsTab(RedPitayaDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        root.getStyleClass().add("cfg-tab-inner");

        var refresh = new Button("Refresh");
        refresh.setOnAction(e -> diagnostics.refresh());
        var pause = new ToggleButton("Pause polling");
        pause.selectedProperty().addListener((o, p, n) -> {
            if (n) diagnostics.stop();
            else diagnostics.start();
        });
        var controls = new HBox(8, refresh, pause);
        controls.setAlignment(Pos.CENTER_LEFT);

        EditorRows.section(root, "Device info", "Static identity advertised by mri-rp-server.");
        root.getChildren().addAll(
            EditorRows.kv("Model",   modelLabel),
            EditorRows.kv("MAC",     macLabel),
            EditorRows.kv("Kernel",  kernelLabel),
            new Separator()
        );

        EditorRows.section(root, "Live stats", "Polled each diagnostics interval.");
        root.getChildren().addAll(
            EditorRows.kv("Uptime",         uptimeLabel),
            EditorRows.kv("CPU temp",       cpuTempLabel),
            EditorRows.kv("Link speed",     linkLabel),
            EditorRows.kv("ADC overflows",  adcOverLabel),
            EditorRows.kv("DAC underruns",  dacUnderLabel),
            EditorRows.kv("Last poll",      lastPollLabel),
            new Separator()
        );

        EditorRows.section(root, "Extras", "Server-provided keys this Java release doesn't recognise yet.");
        root.getChildren().addAll(extrasBox, new Separator(), controls);

        diagnostics.snapshot().addListener((o, p, n) -> apply(n));
        apply(diagnostics.snapshot().get());
    }

    @Override public Node view() { return root; }

    @Override public void refresh(RedPitayaConfig cfg) {
        // No editable state on this tab; the snapshot listener does the rest.
    }

    /** Make the diagnostics snapshot externally observable for tests / Connection tab. */
    ObjectProperty<DiagnosticsSnapshot> snapshotProperty() {
        return diagnostics.snapshot();
    }

    private void apply(DiagnosticsSnapshot snap) {
        if (snap == null) return;
        if (!snap.reachable()) {
            modelLabel.setText("(unreachable)");
            macLabel.setText("--");
            kernelLabel.setText("--");
            uptimeLabel.setText("--");
            cpuTempLabel.setText("--");
            linkLabel.setText("--");
            adcOverLabel.setText("--");
            dacUnderLabel.setText("--");
        } else {
            modelLabel.setText(orDash(snap.model()));
            macLabel.setText(orDash(snap.mac()));
            kernelLabel.setText(orDash(snap.kernel()));
            uptimeLabel.setText(formatUptime(snap.uptimeSeconds()));
            cpuTempLabel.setText(Double.isNaN(snap.cpuTempCelsius()) ? "--" : String.format("%.1f °C", snap.cpuTempCelsius()));
            linkLabel.setText(snap.linkSpeedMbps() == 0 ? "--" : snap.linkSpeedMbps() + " Mbps");
            adcOverLabel.setText(Long.toString(snap.adcOverflowsSinceBoot()));
            dacUnderLabel.setText(Long.toString(snap.dacUnderrunsSinceBoot()));
        }
        lastPollLabel.setText(snap.pollTime().toEpochMilli() == 0 ? "never"
            : DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(snap.pollTime()));

        extrasBox.getChildren().clear();
        for (var entry : snap.extras().entrySet()) {
            extrasBox.getChildren().add(EditorRows.kv(entry.getKey(), entry.getValue()));
        }
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "--" : s;
    }

    private static String formatUptime(long seconds) {
        if (seconds <= 0) return "--";
        var d = Duration.ofSeconds(seconds);
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return String.format("%dd %dh %dm", days, hours, mins);
        if (hours > 0) return String.format("%dh %dm", hours, mins);
        return mins + "m";
    }
}
