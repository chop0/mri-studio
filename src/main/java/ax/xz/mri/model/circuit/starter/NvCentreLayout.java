package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentPosition;

import java.util.List;

/**
 * Hand-tuned layout for the NV-centre diamond starter.
 *
 * <p>Rows top to bottom:
 * <ol>
 *   <li>B0 bias path (STATIC source → Helmholtz coil).</li>
 *   <li>Sample path (STATIC source → Lorentzian-dipole coil).</li>
 *   <li>MW I envelope.</li>
 *   <li>MW Q envelope → Modulator (centre) → MW Coil (right).</li>
 *   <li>Laser GATE → Diamond substance block (centre) → Optical counter
 *       (right), wired from the substance's {@code clicks_red} OPTICAL port.</li>
 * </ol>
 */
final class NvCentreLayout {
    private NvCentreLayout() {}

    private static final double SRC_X        = 160;
    private static final double MOD_X        = 540;
    private static final double COIL_X       = 900;
    private static final double SUBSTANCE_X  = 540;
    private static final double LASER_X      = 320;
    private static final double COUNTER_X    = 760;
    private static final double FIRST_ROW_Y  = 160;
    private static final double ROW_SPACING  = 170;

    /**
     * Arrange every component of the NV-centre starter.
     *
     * @param sources    order [b0, sample, mwI, mwQ]
     * @param coils      order [b0Coil, sampleCoil, mwCoil]
     * @param modulator  MW upconverter
     * @param laserSrc   laser GATE source
     * @param substance  the diamond substance block
     * @param counter    the optical counter wired from substance.clicks_red
     */
    static CircuitLayout arrange(List<CircuitComponent.VoltageSource> sources,
                                 List<CircuitComponent.Coil> coils,
                                 CircuitComponent.Modulator modulator,
                                 CircuitComponent.VoltageSource laserSrc,
                                 CircuitComponent.Substance substance,
                                 CircuitComponent.OpticalCounter counter) {
        var layout = CircuitLayout.empty();

        double b0Y        = FIRST_ROW_Y;
        double sampleY    = FIRST_ROW_Y + 1 * ROW_SPACING;
        double mwIY       = FIRST_ROW_Y + 2 * ROW_SPACING;
        double mwQY       = FIRST_ROW_Y + 3 * ROW_SPACING;
        double mwOutY     = (mwIY + mwQY) / 2.0;
        double gradXY     = FIRST_ROW_Y + 4 * ROW_SPACING;
        double substanceY = FIRST_ROW_Y + 5 * ROW_SPACING + 30;

        layout = layout
            .with(new ComponentPosition(sources.get(0).id(), SRC_X,       b0Y,        0))
            .with(new ComponentPosition(coils.get(0).id(),   COIL_X,      b0Y,        0))
            .with(new ComponentPosition(sources.get(1).id(), SRC_X,       sampleY,    0))
            .with(new ComponentPosition(coils.get(1).id(),   COIL_X,      sampleY,    0))
            .with(new ComponentPosition(sources.get(2).id(), SRC_X,       mwIY,       0))
            .with(new ComponentPosition(sources.get(3).id(), SRC_X,       mwQY,       0))
            .with(new ComponentPosition(modulator.id(),      MOD_X,       mwOutY,     0))
            .with(new ComponentPosition(coils.get(2).id(),   COIL_X,      mwOutY,     0))
            .with(new ComponentPosition(sources.get(4).id(), SRC_X,       gradXY,     0))
            .with(new ComponentPosition(coils.get(3).id(),   COIL_X,      gradXY,     0))
            .with(new ComponentPosition(laserSrc.id(),       LASER_X,     substanceY, 0))
            .with(new ComponentPosition(substance.id(),      SUBSTANCE_X, substanceY, 0))
            .with(new ComponentPosition(counter.id(),        COUNTER_X,   substanceY, 0));

        return layout;
    }
}
