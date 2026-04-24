package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.service.circuit.mna.MnaNetwork;

import java.util.List;

/**
 * Post-compile, simulator-ready view of a {@link ax.xz.mri.model.circuit.CircuitDocument}.
 *
 * <p>The compiler resolves the graph once and produces:
 * <ul>
 *   <li>Typed metadata lists for sources, coils, and probes — the bits the
 *       simulator needs beyond raw MNA topology (channel offsets, sampled
 *       eigenfields, demod carriers, …).</li>
 *   <li>An {@link MnaNetwork} that drives per-step Modified Nodal Analysis in
 *       {@link ax.xz.mri.service.circuit.mna.MnaSolver}: resistor / capacitor /
 *       switch / voltage-branch stamps, plus node / branch index tables.</li>
 * </ul>
 *
 * <p>Switches deliberately don't have a typed metadata entry — their
 * user-visible parameters (closed / open ohms, threshold, ctl binding, invert)
 * all live in the MNA stamps where the solver actually reads them.
 */
public record CompiledCircuit(
    List<CompiledSource> sources,
    List<CompiledCoil> coils,
    List<CompiledProbe> probes,
    MnaNetwork mna,
    int totalChannelCount,
    int[] rfEnvelopeChannelOffsets
) {
    public CompiledCircuit {
        sources = List.copyOf(sources == null ? List.of() : sources);
        coils = List.copyOf(coils == null ? List.of() : coils);
        probes = List.copyOf(probes == null ? List.of() : probes);
        rfEnvelopeChannelOffsets = rfEnvelopeChannelOffsets == null
            ? new int[0] : rfEnvelopeChannelOffsets.clone();
    }

    /**
     * A voltage source with the control-offset layout it consumes per step.
     * {@link #name()} doubles as the DAW track name: tracks whose
     * {@link ax.xz.mri.model.sequence.SequenceChannel#sourceName()} matches
     * it sum into this source.
     */
    public record CompiledSource(
        ComponentId id,
        String name,
        int channelOffset,
        AmplitudeKind kind,
        double carrierHz,
        double staticAmplitude
    ) {
        public int channelCount() { return kind.channelCount(); }
    }

    /**
     * A coil compiled onto the (r, z) grid. {@code ex[ri][zi]} / {@code ey} /
     * {@code ez} are the eigenfield components per grid point, pre-multiplied
     * by the eigenfield's {@code defaultMagnitude}.
     */
    public record CompiledCoil(
        ComponentId id,
        String name,
        double selfInductanceHenry,
        double seriesResistanceOhms,
        double[][] ex,
        double[][] ey,
        double[][] ez
    ) {}

    public record CompiledProbe(
        ComponentId id,
        String name,
        double gain,
        double demodPhaseDeg,
        double loadImpedanceOhms
    ) {}
}
