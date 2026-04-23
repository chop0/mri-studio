package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentPosition;

import java.util.List;

/**
 * Hand-tuned layout for the low-field MRI starter.
 *
 * <p>Puts each source on the same row as the coil it drives and places the
 * RX chain (switch + probe) to the right of the RF coil, so the receive
 * path reads left-to-right as RF -> RF Coil -> RX Switch -> Primary RX.
 * Generic auto-layout tends to muddle this into L-shaped wire routing that
 * looks like the switch is between unrelated components.
 */
final class LowFieldLayout {
    private LowFieldLayout() {}

    private static final double SRC_X = 160;
    private static final double COIL_X = 480;
    private static final double SWITCH_X = 760;
    private static final double PROBE_X = 960;
    private static final double ROW_SPACING = 150;
    private static final double FIRST_ROW_Y = 160;

    /**
     * Arrange the 10 components of the low-field MRI starter. The voltage
     * sources and coils are row-aligned; the RX path extends rightward on
     * the RF row.
     *
     * @param sources order [b0, rf, gx, gz]
     * @param coils   order [b0Coil, rfCoil, gxCoil, gzCoil]
     */
    static CircuitLayout arrange(List<CircuitComponent.VoltageSource> sources,
                                  List<CircuitComponent.Coil> coils,
                                  CircuitComponent.SwitchComponent rxSwitch,
                                  CircuitComponent.Probe probe) {
        var layout = CircuitLayout.empty();
        for (int i = 0; i < 4; i++) {
            double y = FIRST_ROW_Y + i * ROW_SPACING;
            layout = layout
                .with(new ComponentPosition(sources.get(i).id(), SRC_X, y, 0))
                .with(new ComponentPosition(coils.get(i).id(), COIL_X, y, 0));
        }
        double rfY = FIRST_ROW_Y + ROW_SPACING;
        layout = layout
            .with(new ComponentPosition(rxSwitch.id(), SWITCH_X, rfY, 0))
            .with(new ComponentPosition(probe.id(), PROBE_X, rfY, 0));
        return layout;
    }
}
