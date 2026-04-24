package ax.xz.mri.model.circuit.compile;

/**
 * Everything a {@link CircuitStampContext#stampSwitch} call needs to produce
 * one switch-shaped entry in the MNA. Packaged so a multiplexer can build two
 * of these (non-inverting + inverting) from the same {@link CtlBinding}
 * without passing six parameters twice.
 */
public record SwitchParams(
    double closedOhms,
    double openOhms,
    double thresholdVolts,
    CtlBinding ctl,
    boolean invert
) {
    public SwitchParams {
        if (!(closedOhms > 0)) throw new IllegalArgumentException("SwitchParams.closedOhms must be > 0");
        if (!(openOhms > closedOhms)) throw new IllegalArgumentException("SwitchParams.openOhms must exceed closedOhms");
        if (ctl == null) throw new IllegalArgumentException("SwitchParams.ctl must not be null");
    }

    public static SwitchParams of(double closedOhms, double openOhms, double thresholdVolts,
                                  CtlBinding ctl, boolean invert) {
        return new SwitchParams(closedOhms, openOhms, thresholdVolts, ctl, invert);
    }

    public SwitchParams withInvert(boolean newInvert) {
        return new SwitchParams(closedOhms, openOhms, thresholdVolts, ctl, newInvert);
    }
}
