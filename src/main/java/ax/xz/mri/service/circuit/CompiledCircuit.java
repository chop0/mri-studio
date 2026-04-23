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
 *   <li>Parallel lists describing sources, coils, probes, and switches — their
 *       user-facing metadata (names, carrier, gain, etc.).</li>
 *   <li>An {@link MnaNetwork} that drives per-step Modified Nodal Analysis in
 *       {@link ax.xz.mri.service.circuit.mna.MnaSolver}: resistor / capacitor /
 *       switch / voltage-branch stamps, plus node / branch index tables.</li>
 * </ul>
 *
 * <p>The simulator never re-walks the graph; it just reads the MNA network
 * each step.
 */
public record CompiledCircuit(
    List<CompiledSource> sources,
    List<CompiledSwitch> switches,
    List<CompiledCoil> coils,
    List<CompiledProbe> probes,
    MnaNetwork mna,
    int totalChannelCount
) {
    public CompiledCircuit {
        sources = List.copyOf(sources == null ? List.of() : sources);
        switches = List.copyOf(switches == null ? List.of() : switches);
        coils = List.copyOf(coils == null ? List.of() : coils);
        probes = List.copyOf(probes == null ? List.of() : probes);
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
     * A switch. The runtime {@link ax.xz.mri.service.circuit.mna.MnaSolver}
     * reads the corresponding {@link MnaNetwork} entry to figure out its ctl
     * binding and invert flag; this record exists purely to hold user-visible
     * metadata (id, name, and the thresholds for inspection).
     */
    public record CompiledSwitch(
        ComponentId id,
        String name,
        double closedOhms,
        double openOhms,
        double thresholdVolts,
        boolean invertCtl
    ) {}

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
        double carrierHz,
        double demodPhaseDeg,
        double loadImpedanceOhms
    ) {}
}
