package ax.xz.mri.service.circuit.path;

import ax.xz.mri.model.circuit.CircuitComponent.Coil;
import ax.xz.mri.model.circuit.ComponentId;

import java.util.List;

/**
 * One coil's worth of analysis output from {@link ClipPathAnalyzer}: which coil
 * the source can drive, the wire-graph path to get there, and a cosmetic
 * estimate of voltage→current gain at the local working frequency.
 *
 * <p>The numbers here are <em>preview</em>-grade — a quick voltage-divider
 * walk through the path's series impedance, with the coil's own R + jωL at
 * the end. They aren't a substitute for the MNA: shunts, reactive coupling
 * between branches, and switch-state-dependent routing collapse to either a
 * "best case" (closed switches) or a warning string. The simulator's
 * {@code MnaSolver} remains the source of truth for actual coil currents.
 *
 * <h3>Field math</h3>
 * <p>Given the source amplitude {@code V}:
 * <pre>
 *   I_coil   = V × |currentGain|                  (amperes)
 *   B(r)     = I_coil × coil.sensitivityT_per_A × eigenfield_shape(r)
 *   |B|peak  = max over the FOV of |B(r)|         (tesla)
 * </pre>
 *
 * <p>{@code frequencyHz} is the carrier the signal arrives at the coil at —
 * useful both for showing the user and for the impedance calculation that
 * produced {@code currentGain}. A modulator in the path stamps its
 * {@code loHz} as the new carrier; a mixer subtracts its {@code loHz}.
 *
 * @param coil the destination coil
 * @param componentsOnPath ordered list of component ids on the path, source first, coil last;
 *                         pass-throughs (resistors, switches, modulators, &c.) are included so
 *                         the schematic highlight covers the whole signal route
 * @param wireIdsOnPath ids of wires connecting consecutive components on the path
 * @param voltageGain magnitude of {@code V_coil / V_source} (unitless, ≥ 0)
 * @param currentGainPerVolt magnitude of {@code I_coil / V_source} (amperes per volt)
 * @param frequencyHz carrier frequency at the coil terminals (Hz)
 * @param warnings human-readable caveats — open switches, shunts, multiple modulators, etc.
 */
public record CoilPath(
    Coil coil,
    List<ComponentId> componentsOnPath,
    List<String> wireIdsOnPath,
    double voltageGain,
    double currentGainPerVolt,
    double frequencyHz,
    List<String> warnings
) {
    public CoilPath {
        if (coil == null) throw new IllegalArgumentException("CoilPath.coil must not be null");
        componentsOnPath = List.copyOf(componentsOnPath);
        wireIdsOnPath = List.copyOf(wireIdsOnPath);
        warnings = List.copyOf(warnings);
    }

    /** Coil current magnitude given the source's clip amplitude. */
    public double currentAmpsAt(double sourceAmplitudeVolts) {
        return Math.abs(sourceAmplitudeVolts) * currentGainPerVolt;
    }
}
