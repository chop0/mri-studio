package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.DiagnosticsSnapshot;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaDiagnostics;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaDiscovery;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Connection-tab UI: hostname text field, mDNS detect popover, reachability
 * indicator, RTT, server version, and the deploy hint shown when the server
 * isn't responding.
 */
final class ConnectionTab implements ConfigTab {

    private final EditContext ctx;
    private final RedPitayaDiscovery discovery;
    private final ObjectProperty<DiagnosticsSnapshot> diagnostics;
    private final VBox root = new VBox(10);

    private final TextField hostField = new TextField();
    private final NumberField portField = new NumberField().range(1, 65535).step(1).decimals(0);
    private final NumberField timeoutField = new NumberField().range(50, 60_000).step(50).decimals(0).unit("ms");
    private final NumberField pollField = new NumberField().range(500, 60_000).step(500).decimals(0).unit("ms");
    private final Button detectButton = new Button("Detect");
    private final Circle reachableDot = new Circle(6);
    private final Label reachableLabel = new Label("unknown");
    private final Label rttLabel = new Label();
    private final Label versionLabel = new Label();
    private final Label deployHint = new Label();

    private boolean suppressEdits;

    ConnectionTab(EditContext ctx,
                  RedPitayaDiscovery discovery,
                  ObjectProperty<DiagnosticsSnapshot> diagnostics) {
        this.ctx = ctx;
        this.discovery = discovery;
        this.diagnostics = diagnostics;

        root.getStyleClass().add("cfg-tab-inner");

        hostField.setPromptText("rp-XXXXXX.local");
        hostField.setPrefColumnCount(24);
        var hostBox = new HBox(8, hostField, detectButton);
        hostBox.setAlignment(Pos.CENTER_LEFT);

        reachableDot.setFill(Color.GRAY);
        var reachable = new HBox(8, reachableDot, reachableLabel);
        reachable.setAlignment(Pos.CENTER_LEFT);

        deployHint.setWrapText(true);
        deployHint.getStyleClass().add("cfg-row-hint");

        EditorRows.section(root, "Network", "Hostname or address of the on-Pitaya server. Click Detect "
            + "to pick from the mDNS-discovered list of nearby Red Pitayas.");
        root.getChildren().addAll(
            EditorRows.row("Hostname",       hostBox,      ""),
            EditorRows.row("Port",           portField,    "Default 6981; matches mri-rp-server"),
            EditorRows.row("Connect timeout", timeoutField, "Per-attempt socket connect deadline"),
            new Separator()
        );

        EditorRows.section(root, "Status", "Live reachability and version of the running mri-rp-server. "
            + "Updated every poll interval.");
        root.getChildren().addAll(
            EditorRows.row("Reachable",   reachable,    ""),
            EditorRows.row("RTT",         rttLabel,     "Round-trip on the diagnostics probe"),
            EditorRows.row("Server",      versionLabel, "Build sha advertised by the server"),
            EditorRows.row("Poll period", pollField,    "How often diagnostics are refreshed"),
            EditorRows.row("",            deployHint,   "")
        );

        wireListeners();
        diagnostics.addListener((o, p, n) -> applyDiagnostics(n));
        applyDiagnostics(diagnostics.get());
        refresh(ctx.current());
    }

    @Override public Node view() { return root; }

    @Override
    public void refresh(RedPitayaConfig cfg) {
        suppressEdits = true;
        try {
            hostField.setText(cfg.hostname());
            portField.setValueQuiet(cfg.port());
            timeoutField.setValueQuiet(cfg.connectTimeoutMs());
            pollField.setValueQuiet(cfg.diagnosticPollMs());
        } finally { suppressEdits = false; }
    }

    private void wireListeners() {
        hostField.textProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withHostname(n.trim()));
        });
        portField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withPort(n.intValue()));
        });
        timeoutField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withConnectTimeoutMs(n.intValue()));
        });
        pollField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withDiagnosticPollMs(n.intValue()));
        });
        detectButton.setOnAction(e -> showDetectMenu());
    }

    private void showDetectMenu() {
        var menu = new ContextMenu();
        var hosts = discovery.hosts();
        if (hosts.isEmpty()) {
            var empty = new MenuItem("No Red Pitayas detected (waiting on mDNS...)");
            empty.setDisable(true);
            menu.getItems().add(empty);
        } else {
            for (var host : hosts) {
                var item = new MenuItem(host.hostname() + (host.ip().isEmpty() ? "" : "  (" + host.ip() + ")"));
                item.setOnAction(e -> hostField.setText(host.hostname()));
                menu.getItems().add(item);
            }
        }
        var bounds = detectButton.localToScreen(detectButton.getBoundsInLocal());
        menu.show(detectButton, bounds.getMinX(), bounds.getMaxY());
    }

    private void applyDiagnostics(DiagnosticsSnapshot snap) {
        if (snap == null) return;
        if (snap.reachable()) {
            reachableDot.setFill(Color.LIMEGREEN);
            reachableLabel.setText("connected");
            rttLabel.setText(String.format("%.1f ms", snap.rttMs()));
            versionLabel.setText(snap.serverGitSha().isEmpty() ? "(no sha advertised)" : snap.serverGitSha());
            deployHint.setText("");
        } else {
            reachableDot.setFill(snap.pollTime().toEpochMilli() == 0 ? Color.GRAY : Color.ORANGERED);
            reachableLabel.setText(snap.pollTime().toEpochMilli() == 0 ? "unknown" : "unreachable");
            rttLabel.setText("--");
            versionLabel.setText("--");
            deployHint.setText("If the host is up but the server isn't responding, build and deploy "
                + "from the mri-rp-server/ directory: ./deploy.sh " + ctx.current().hostname());
        }
    }
}
