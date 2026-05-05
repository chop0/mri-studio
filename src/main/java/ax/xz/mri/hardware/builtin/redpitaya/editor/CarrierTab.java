package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaSampleRate;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaTxPort;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

/**
 * Carrier frequencies, ADC/DAC decimation, TX gain, and TX output port.
 */
final class CarrierTab implements ConfigTab {

    private final EditContext ctx;
    private final VBox root = new VBox(10);

    private final NumberField txField    = new NumberField().range(0, 6e7).step(1e3).scientific();
    private final NumberField rxField    = new NumberField().range(0, 6e7).step(1e3).scientific();
    private final CheckBox    lockRxToTx = new CheckBox("Lock RX to TX");
    private final ChoiceBox<RedPitayaSampleRate> rateBox = new ChoiceBox<>();
    private final NumberField gainField  = new NumberField().range(0, 1).step(0.05).decimals(3);
    private final ChoiceBox<RedPitayaTxPort> portBox = new ChoiceBox<>();

    private final Label rateInfo = new Label();
    private final Label gainDb   = new Label();

    private boolean suppressEdits;

    // Test-only accessors (package-private) so the editor's lock + dirty
    // semantics are pinned down by a unit test without reaching for JavaFX
    // scene-graph lookups.
    NumberField txFieldForTest()              { return txField; }
    NumberField rxFieldForTest()              { return rxField; }
    javafx.scene.control.CheckBox lockForTest() { return lockRxToTx; }

    CarrierTab(EditContext ctx) {
        this.ctx = ctx;

        rateBox.getItems().setAll(RedPitayaSampleRate.values());
        rateBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(RedPitayaSampleRate r) { return r == null ? "" : r.label(); }
            @Override public RedPitayaSampleRate fromString(String s) { return null; }
        });
        portBox.getItems().setAll(RedPitayaTxPort.values());

        root.getStyleClass().add("cfg-tab-inner");

        EditorRows.section(root, "Carrier", "I/Q baseband from the studio is upconverted on the "
            + "Pitaya's ARM CPU to the chosen TX carrier and emitted from the selected DAC port. "
            + "The RX path downconverts the captured ADC stream by the RX carrier into baseband I/Q.");
        root.getChildren().addAll(
            EditorRows.row("TX carrier", txField,    "Hz; centre frequency emitted on TX"),
            EditorRows.row("RX carrier", rxField,    "Hz; centre frequency the host downconverts by"),
            EditorRows.row("",           lockRxToTx, "Drives RX carrier from the TX value while ticked"),
            new Separator()
        );

        EditorRows.section(root, "Sample rate", "Effective baseband I/Q rate after on-chip "
            + "decimation. Bandwidth is half the rate; minimum dt is one over the rate.");
        root.getChildren().addAll(
            EditorRows.row("Decimation", rateBox,  "1 = 125 MS/s; higher decimation lowers rate and bandwidth"),
            EditorRows.row("",           rateInfo, ""),
            new Separator()
        );

        EditorRows.section(root, "Transmit", "Per-run amplitude scaling and choice of which "
            + "physical RF output the carrier-mixed signal is driven from.");
        root.getChildren().addAll(
            EditorRows.row("TX gain",     gainField, "0..1 amplitude scale before DAC encoding"),
            EditorRows.row("",            gainDb,    ""),
            EditorRows.row("Output port", portBox,   "OUT1 or OUT2; both routable by librp")
        );

        wireListeners();
        refresh(ctx.current());
    }

    @Override public Node view() { return root; }

    @Override
    public void refresh(RedPitayaConfig cfg) {
        suppressEdits = true;
        try {
            txField.setValueQuiet(cfg.txCarrierHz());
            rxField.setValueQuiet(cfg.rxCarrierHz());
            lockRxToTx.setSelected(cfg.txCarrierHz() == cfg.rxCarrierHz());
            rateBox.setValue(cfg.sampleRate());
            gainField.setValueQuiet(cfg.txGain());
            portBox.setValue(cfg.txPort());
            updateDerivedLabels();
        } finally { suppressEdits = false; }
    }

    private void wireListeners() {
        // Bidirectional lock: typing into either field while locked snaps the
        // other field's display AND pushes both values into the config, so the
        // dirty-tracker sees one coherent change instead of a misleading half.
        txField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            double v = n.doubleValue();
            if (lockRxToTx.isSelected()) {
                suppressEdits = true;
                try { rxField.setValueQuiet(v); } finally { suppressEdits = false; }
                ctx.edit(c -> c.withTxCarrierHz(v).withRxCarrierHz(v));
            } else {
                ctx.edit(c -> c.withTxCarrierHz(v));
            }
            updateDerivedLabels();
        });
        rxField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            double v = n.doubleValue();
            if (lockRxToTx.isSelected()) {
                suppressEdits = true;
                try { txField.setValueQuiet(v); } finally { suppressEdits = false; }
                ctx.edit(c -> c.withTxCarrierHz(v).withRxCarrierHz(v));
            } else {
                ctx.edit(c -> c.withRxCarrierHz(v));
            }
            updateDerivedLabels();
        });
        // Toggling the lock is purely a UI affordance — only push an edit when
        // turning it ON snaps RX to TX. Turning it off changes nothing in the
        // config, so it must not dirty the document.
        lockRxToTx.selectedProperty().addListener((o, p, n) -> {
            if (suppressEdits) return;
            if (n) {
                double v = txField.getValue();
                suppressEdits = true;
                try { rxField.setValueQuiet(v); } finally { suppressEdits = false; }
                ctx.edit(c -> c.withRxCarrierHz(v));
            }
        });
        rateBox.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withSampleRate(n));
            updateDerivedLabels();
        });
        gainField.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withTxGain(n.doubleValue()));
            updateDerivedLabels();
        });
        portBox.valueProperty().addListener((o, p, n) -> {
            if (suppressEdits || n == null) return;
            ctx.edit(c -> c.withTxPort(n));
        });
    }

    private void updateDerivedLabels() {
        var rate = rateBox.getValue();
        if (rate != null) {
            rateInfo.setText(String.format(
                "  bandwidth = %.3f MHz, min dt = %.3f ns",
                rate.maxBandwidthHz() * 1e-6, rate.minDtSeconds() * 1e9));
        }
        double g = gainField.getValue();
        if (g <= 0) gainDb.setText("  -inf dB");
        else gainDb.setText(String.format("  = %.2f dBFS", 20.0 * Math.log10(g)));
    }
}
