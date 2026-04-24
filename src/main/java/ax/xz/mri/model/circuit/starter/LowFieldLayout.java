package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentPosition;

import java.util.List;

/**
 * Hand-tuned layout for the low-field MRI starter.
 *
 * <p>Puts each source on the same row as the coil it drives and places the
 * RX chain (mux → I/Q demod → probe) to the right of the RF coil, so the
 * receive path reads left-to-right as RF -> mux -> demod -> Primary RX.
 * Generic auto-layout tends to muddle this into L-shaped wire routing that
 * looks like the mux is between unrelated components.
 */
final class LowFieldLayout {
    private LowFieldLayout() {}

    private static final double SRC_X = 160;
    private static final double COIL_X = 480;
    private static final double ROW_SPACING = 150;
    private static final double FIRST_ROW_Y = 160;

    /**
     * Arrange the 11 components of the low-field MRI starter. The voltage
     * sources and coils are row-aligned; the T/R mux sits between the RF
     * source and the RF coil on the RF row; the Mixer and probe
     * sit below it on a dedicated RX row.
     *
     * @param sources order [b0, rf, gx, gz]
     * @param coils   order [b0Coil, rfCoil, gxCoil, gzCoil]
     */
    static CircuitLayout arrange(List<CircuitComponent.VoltageSource> sources,
                                  List<CircuitComponent.Coil> coils,
                                  CircuitComponent.Multiplexer mux,
                                  CircuitComponent.VoltageMetadata rfActive,
                                  CircuitComponent.Mixer mixer,
                                  CircuitComponent.Probe probe) {
        var layout = CircuitLayout.empty();
        for (int i = 0; i < 4; i++) {
            double y = FIRST_ROW_Y + i * ROW_SPACING;
            layout = layout
                .with(new ComponentPosition(sources.get(i).id(), SRC_X, y, 0))
                .with(new ComponentPosition(coils.get(i).id(), COIL_X, y, 0));
        }
        double rfY = FIRST_ROW_Y + ROW_SPACING;
        double muxX = (SRC_X + COIL_X) / 2.0;
        layout = layout.with(new ComponentPosition(mux.id(), muxX, rfY, 0));
        // Metadata tap sits between the RF source and the mux's ctl so
        // "RF playing -> TX mode" reads left-to-right on the schematic.
        layout = layout.with(new ComponentPosition(rfActive.id(), muxX - 60, rfY + 60, 0));
        // Drop below the mux: mixer then probe, stacked so the signal flow
        // reads top-to-bottom on the RX side.
        layout = layout.with(new ComponentPosition(mixer.id(), muxX + 20, rfY + 90, 0));
        layout = layout.with(new ComponentPosition(probe.id(), muxX + 140, rfY + 90, 0));
        return layout;
    }
}
