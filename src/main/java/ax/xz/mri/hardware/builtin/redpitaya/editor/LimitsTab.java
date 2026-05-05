package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

/**
 * Static derived limits — gives the user a sense of what fits at the
 * current carrier / decimation choice without having to look at server
 * source.
 */
final class LimitsTab implements ConfigTab {

    private static final long DDR3_BYTES = 4L * 1024 * 1024 * 1024;
    /** Bytes per post-decimation RX sample: float I + float Q. */
    private static final long BYTES_PER_RX_SAMPLE = 8;

    private final VBox root = new VBox(10);
    private final Label maxRxLabel       = new Label();
    private final Label nyquistLabel     = new Label();
    private final Label baseClockLabel   = new Label();
    private final Label sustainedLabel   = new Label();

    LimitsTab() {
        root.getStyleClass().add("cfg-tab-inner");

        EditorRows.section(root, "Streaming budget", "DDR3-backed AXI-DMA streaming has practical "
            + "ceilings; this view shows them at the current decimation. The numbers update on each "
            + "config change.");
        root.getChildren().addAll(
            EditorRows.kv("DDR3 capture cap",  new Label(humanBytes(DDR3_BYTES))),
            EditorRows.kv("Max RX duration",   maxRxLabel),
            EditorRows.kv("Sustained TX/RX",   sustainedLabel),
            new Separator()
        );

        EditorRows.section(root, "Frequency budget", "Nyquist limits and the FPGA's base clock.");
        root.getChildren().addAll(
            EditorRows.kv("Base clock",        baseClockLabel),
            EditorRows.kv("Carrier vs Nyquist", nyquistLabel)
        );
    }

    @Override public Node view() { return root; }

    @Override
    public void refresh(RedPitayaConfig cfg) {
        var rate = cfg.sampleRate();
        long maxSamples = DDR3_BYTES / BYTES_PER_RX_SAMPLE;
        double maxSeconds = maxSamples / rate.effectiveRateHz();
        maxRxLabel.setText(String.format("%.2f s @ %.3f MS/s", maxSeconds, rate.effectiveRateHz() * 1e-6));

        baseClockLabel.setText("125 MHz");
        sustainedLabel.setText(String.format("~%.0f MB/s typical (link- and TCP-limited)",
            BYTES_PER_RX_SAMPLE * rate.effectiveRateHz() / 1e6));

        double nyquist = rate.effectiveRateHz() * 0.5;
        double maxCarrier = Math.max(cfg.txCarrierHz(), cfg.rxCarrierHz());
        if (maxCarrier > nyquist) {
            nyquistLabel.setText(String.format("WARN: %.3f MHz > Nyquist %.3f MHz",
                maxCarrier * 1e-6, nyquist * 1e-6));
        } else {
            nyquistLabel.setText(String.format("%.3f MHz / %.3f MHz Nyquist",
                maxCarrier * 1e-6, nyquist * 1e-6));
        }
    }

    private static String humanBytes(long bytes) {
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, units[i]);
    }
}
