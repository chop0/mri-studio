package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentPosition;

import java.util.List;

/**
 * Hand-tuned layout for the low-field MRI starter.
 *
 * <p>Rows from top to bottom: B0 | RF I | RF Q | RX path | Gx | Gz. The
 * Modulator sits between the two RF envelopes and the T/R mux, turning
 * baseband I/Q into RF at the Larmor carrier. Each row has its own
 * vertical slot so the metadata tap, the mux, the downconverting mixer,
 * and the primary RX probe read left-to-right without colliding with the
 * coil rows above and below.
 */
final class LowFieldLayout {
    private LowFieldLayout() {}

    private static final double SRC_X = 160;
    private static final double MODULATOR_X = 300;
    private static final double MUX_X = 440;
    private static final double META_X = 260;
    private static final double MIXER_X = 560;
    private static final double PROBE_X = 720;
    private static final double COIL_X = 900;

    private static final double ROW_SPACING = 170;
    private static final double FIRST_ROW_Y = 160;
    private static final double RX_ROW_OFFSET = 170;

    /**
     * Arrange the 13 components of the low-field MRI starter.
     *
     * @param sources order [b0, rfI, rfQ, gx, gz]
     * @param coils   order [b0Coil, rfCoil, gxCoil, gzCoil]
     */
    static CircuitLayout arrange(List<CircuitComponent.VoltageSource> sources,
                                  List<CircuitComponent.Coil> coils,
                                  CircuitComponent.Modulator rfModulator,
                                  CircuitComponent.Multiplexer mux,
                                  CircuitComponent.VoltageMetadata rfActive,
                                  CircuitComponent.Mixer mixer,
                                  CircuitComponent.Probe probe) {
        var layout = CircuitLayout.empty();

        // B0 on row 0.
        double b0Y = FIRST_ROW_Y;
        layout = layout
            .with(new ComponentPosition(sources.get(0).id(), SRC_X, b0Y, 0))
            .with(new ComponentPosition(coils.get(0).id(), COIL_X, b0Y, 0));

        // RF I on row 1 and RF Q on row 2. The Modulator straddles the two
        // between the source column and the mux column.
        double rfIY = FIRST_ROW_Y + ROW_SPACING;
        double rfQY = FIRST_ROW_Y + 2 * ROW_SPACING;
        double rfMidY = (rfIY + rfQY) / 2.0;
        layout = layout
            .with(new ComponentPosition(sources.get(1).id(), SRC_X, rfIY, 0))
            .with(new ComponentPosition(sources.get(2).id(), SRC_X, rfQY, 0))
            .with(new ComponentPosition(rfModulator.id(), MODULATOR_X, rfMidY, 0))
            .with(new ComponentPosition(coils.get(1).id(), COIL_X, rfMidY, 0))
            .with(new ComponentPosition(mux.id(), MUX_X, rfMidY, 0));

        // RX path below the RF cluster.
        double rxY = rfQY + RX_ROW_OFFSET;
        layout = layout
            .with(new ComponentPosition(rfActive.id(), META_X, rxY, 0))
            .with(new ComponentPosition(mixer.id(), MIXER_X, rxY, 0))
            .with(new ComponentPosition(probe.id(), PROBE_X, rxY, 0));

        // Gradient rows below the RX row.
        double gxY = rxY + ROW_SPACING;
        double gzY = gxY + ROW_SPACING;
        layout = layout
            .with(new ComponentPosition(sources.get(3).id(), SRC_X, gxY, 0))
            .with(new ComponentPosition(coils.get(2).id(), COIL_X, gxY, 0))
            .with(new ComponentPosition(sources.get(4).id(), SRC_X, gzY, 0))
            .with(new ComponentPosition(coils.get(3).id(), COIL_X, gzY, 0));

        return layout;
    }
}
