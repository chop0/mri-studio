package ax.xz.mri.hardware.builtin;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Dense, sectioned editor for {@link MockHardwareConfig}. Built to feel like
 * the rest of the studio — same {@code .cfg-*} CSS classes used by the
 * sim-config editor, same {@link NumberField} primitive, label-on-the-left
 * rows with a unit suffix and an explanatory hint.
 *
 * <p>Two sections:
 * <ul>
 *   <li><b>Synthetic ADC</b> — controls how the fake echo response looks
 *       (delay, decay, additive noise).</li>
 *   <li><b>Connection</b> — fake connect/disconnect timing so the run-session
 *       progress bar has something to animate.</li>
 * </ul>
 *
 * <p>Edits update an internal {@link MockHardwareConfig} immediately and
 * notify the host via {@link #setOnEdited(Runnable)}, so the host can
 * persist live without explicit Apply / Save buttons.
 */
public final class MockHardwareConfigEditor implements HardwareConfigEditor {

    private final VBox root = new VBox(10);
    private final NumberField noiseField;
    private final NumberField echoDelayField;
    private final NumberField echoDecayField;
    private final NumberField connectField;
    private MockHardwareConfig current;
    private Runnable onEdited = () -> {};
    private boolean suppressEdits;

    public MockHardwareConfigEditor(MockHardwareConfig initial) {
        this.current = initial != null ? initial : MockHardwareConfig.defaults();

        root.getStyleClass().addAll("cfg-tab-inner");

        // ── Section: synthetic ADC ──────────────────────────────────────────
        var adcTitle = new Label("Synthetic ADC");
        adcTitle.getStyleClass().add("cfg-section-title");
        var adcSub = new Label(
            "The mock device returns an echo whose timing, decay and noise are " +
            "set here. Useful for verifying the run pipeline without real hardware.");
        adcSub.getStyleClass().add("cfg-section-subtitle");
        adcSub.setWrapText(true);

        noiseField = new NumberField().range(0, 1).step(0.005).decimals(3);
        noiseField.setValue(current.noiseLevel());
        noiseField.valueProperty().addListener((obs, o, n) -> {
            if (!suppressEdits && n != null) {
                current = current.withNoiseLevel(n.doubleValue());
                onEdited.run();
            }
        });

        echoDelayField = new NumberField().range(0, 100_000).step(50).decimals(0).unit("μs");
        echoDelayField.setValue(current.echoDelayMicros());
        echoDelayField.valueProperty().addListener((obs, o, n) -> {
            if (!suppressEdits && n != null) {
                current = current.withEchoDelayMicros(n.doubleValue());
                onEdited.run();
            }
        });

        echoDecayField = new NumberField().range(0.1, 10_000).step(5).decimals(2).unit("Hz");
        echoDecayField.setValue(current.echoDecayHz());
        echoDecayField.valueProperty().addListener((obs, o, n) -> {
            if (!suppressEdits && n != null) {
                current = current.withEchoDecayHz(n.doubleValue());
                onEdited.run();
            }
        });

        // ── Section: connection ─────────────────────────────────────────────
        var connTitle = new Label("Connection");
        connTitle.getStyleClass().add("cfg-section-title");
        var connSub = new Label(
            "How long {@code open()} pretends to take when the run session " +
            "first contacts the device. Useful for exercising the progress bar.");
        connSub.getStyleClass().add("cfg-section-subtitle");
        connSub.setWrapText(true);

        connectField = new NumberField().range(0, 30_000).step(50).decimals(0).unit("ms");
        connectField.setValue(current.connectionDelayMillis());
        connectField.valueProperty().addListener((obs, o, n) -> {
            if (!suppressEdits && n != null) {
                current = current.withConnectionDelayMillis(n.doubleValue());
                onEdited.run();
            }
        });

        root.getChildren().addAll(
            adcTitle, adcSub,
            row("Noise level", noiseField, "σ (a.u.) of additive Gaussian noise per sample"),
            row("Echo delay", echoDelayField, "Time from drive to peak echo response"),
            row("Echo decay", echoDecayField, "Exponential decay of the echo envelope"),
            new javafx.scene.control.Separator(),
            connTitle, connSub,
            row("Connection delay", connectField, "Synthetic latency for connect/teardown")
        );
    }

    @Override
    public Node view() { return root; }

    @Override
    public void setConfig(HardwareConfig config) {
        if (!(config instanceof MockHardwareConfig mc)) return;
        this.current = mc;
        suppressEdits = true;
        try {
            noiseField.setValueQuiet(mc.noiseLevel());
            echoDelayField.setValueQuiet(mc.echoDelayMicros());
            echoDecayField.setValueQuiet(mc.echoDecayHz());
            connectField.setValueQuiet(mc.connectionDelayMillis());
        } finally {
            suppressEdits = false;
        }
    }

    @Override
    public HardwareConfig snapshot() { return current; }

    @Override
    public void setOnEdited(Runnable listener) {
        this.onEdited = listener != null ? listener : () -> {};
    }

    private Node row(String label, Node control, String hint) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-row-label");
        var hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("cfg-row-hint");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var row = new HBox(8, l, control, spacer, hintLabel);
        row.getStyleClass().add("cfg-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
