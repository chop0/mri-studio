package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.Substance;

import java.util.List;

/**
 * Per-substance compute kernel built once at compile time.
 *
 * <p>The {@link CompiledSimulation} pattern-matches over {@link Substance}
 * subtypes during {@link CompiledSimulation#compile} and produces one
 * {@code CompiledSubstance} per substance instance. The kernel owns:
 *
 * <ul>
 *   <li>The substance's spin positions ({@link #spinPosition}).</li>
 *   <li>The size of its state slice in the fused state vector ({@link #stateSize}).</li>
 *   <li>How to {@link #reset reset} that slice.</li>
 *   <li>How to {@link #advance advance} that slice given a local-B field at every spin.</li>
 *   <li>How to {@link #emitMagneticMoments emit} a per-spin dipole moment (for
 *       reciprocity coupling, baked into the simulation).</li>
 *   <li>How to {@link #emitPhotonClickRates emit} per-spin per-channel
 *       photon rates (for optical probes wired to the substance).</li>
 * </ul>
 *
 * <p>This is the only seam the simulator inner loop touches — every cross-kind
 * dispatch happens at compile time, not per-step. New substance kinds add a
 * new {@code Substance} subtype, a new {@code CompiledSubstance} impl, and a
 * new branch in {@link CompiledSimulation#compileSubstance}; the per-step
 * kernel chain in {@code CompiledSimulation.step} is type-agnostic.
 */
public interface CompiledSubstance {

    /** The substance this kernel was compiled from. Held for inspection / debugging. */
    Substance source();

    /** Number of spins this substance contributes. */
    int spinCount();

    /** Position of spin {@code i} (metres). */
    Vec3 spinPosition(int i);

    /** Number of {@code double} slots this substance owns in the fused state vector. */
    int stateSize();

    /** Initialise the state slice starting at {@code offset} to equilibrium. */
    void reset(double[] state, int offset);

    /**
     * Advance the state slice by {@code dt}.
     *
     * <p>{@code localBField} has length {@code 3 * spinCount()} and carries the
     * total local B vector at every spin in this substance's order:
     * {@code localBField[3*i + 0..2] = (Bx, By, Bz)} at spin {@code i}. The
     * caller has already folded coil drives, the rotating-frame reference
     * offset, and any per-substance bias into those values.
     *
     * <p>{@code controlInputs} carries the per-step scalar value of each
     * control input the substance declared via {@link #controlInputNames()},
     * in the same order. Length is {@link #controlInputCount()}. Substances
     * with no control inputs receive a zero-length array (never null).
     */
    void advance(double[] state, int offset,
                 double[] localBField, double[] controlInputs,
                 double dt, double tSeconds);

    /** Number of control inputs this substance consumes. Zero ⇒ no control surface. */
    default int controlInputCount() { return 0; }

    /**
     * Per-control-input names in the order {@link #advance}'s
     * {@code controlInputs} array carries them. Wiring at compile time
     * matches a circuit wire's destination port name against entries here.
     */
    default List<String> controlInputNames() { return List.of(); }

    /**
     * Emit each spin's magnetic dipole moment (Tesla·m³ / μ₀, matching the
     * legacy Bloch convention) into {@code momentsOut} layout
     * {@code momentsOut[3*i + 0..2] = (mx, my, mz)}. Substances that don't
     * couple via {@link ax.xz.mri.model.substance.output.MagneticMoment} (or
     * whose emission is below the simulator noise floor) write zeros.
     */
    void emitMagneticMoments(double[] state, int offset, double[] momentsOut);

    /** Number of optical output channels this substance exposes. Zero ⇒ no optical output. */
    default int opticalChannelCount() { return 0; }

    /** Channel names exposed on the substance's optical output port set. */
    default List<String> opticalChannelNames() { return List.of(); }

    /**
     * Emit per-spin per-channel photon rates (Hz) into {@code ratesOut} with layout
     * {@code ratesOut[i * opticalChannelCount() + c]}.
     */
    default void emitPhotonClickRates(double[] state, int offset, double[] ratesOut) {}
}
