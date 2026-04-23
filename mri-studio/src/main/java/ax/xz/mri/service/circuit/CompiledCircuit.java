package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.AmplitudeKind;

import java.util.List;

/**
 * Post-compile, simulator-ready view of a {@link ax.xz.mri.model.circuit.CircuitDocument}.
 *
 * <p>The compiler union-finds terminals across wires to resolve the graph
 * once, then pre-computes, for every coil, which source drives it through
 * which switches and which probes observe it through which switches. The
 * simulator replays that resolved topology each step — a tree-walk, not a
 * full nodal-analysis solve.
 *
 * <p>Passive lumped components ({@link CompiledPassive resistors, capacitors,
 * inductors}) are stored as data but are currently metadata-only: the walker
 * honours ideal source-coil-probe links. A future MNA milestone will honour
 * matching networks, ring-down, and mutual inductance.
 */
public record CompiledCircuit(
    List<CompiledSource> sources,
    List<CompiledSwitch> switches,
    List<CompiledCoil> coils,
    List<CompiledProbe> probes,
    List<TopologyLink> drives,
    List<TopologyLink> observes,
    List<CompiledPassive> resistors,
    List<CompiledPassive> capacitors,
    List<CompiledPassive> inductors,
    int totalChannelCount
) {
    public CompiledCircuit {
        sources = List.copyOf(sources == null ? List.of() : sources);
        switches = List.copyOf(switches == null ? List.of() : switches);
        coils = List.copyOf(coils == null ? List.of() : coils);
        probes = List.copyOf(probes == null ? List.of() : probes);
        drives = List.copyOf(drives == null ? List.of() : drives);
        observes = List.copyOf(observes == null ? List.of() : observes);
        resistors = List.copyOf(resistors == null ? List.of() : resistors);
        capacitors = List.copyOf(capacitors == null ? List.of() : capacitors);
        inductors = List.copyOf(inductors == null ? List.of() : inductors);
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
     * A switch. {@link #ctlSourceIndex()} is the index into
     * {@link CompiledCircuit#sources()} of the source whose value drives the
     * switch's {@code ctl} port — or {@code -1} if the ctl is floating (in
     * which case the switch is treated as permanently open).
     */
    public record CompiledSwitch(
        ComponentId id,
        String name,
        int ctlSourceIndex,
        double closedOhms,
        double openOhms,
        double thresholdVolts
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
        double demodPhaseDeg,
        double loadImpedanceOhms
    ) {}

    /**
     * A resolved path either from a source to a coil ({@link CompiledCircuit#drives})
     * or from a coil to a probe ({@link CompiledCircuit#observes}). The path
     * is live only when every switch in {@link #switchIndices()} reads as closed.
     *
     * <p>{@link #forwardPolarity()} captures whether the path's {@code (+)}
     * end (source {@code a} or probe {@code a}) lines up with the coil's
     * {@code a} port; when it's {@code false} the driven current (or observed
     * EMF) is inverted.
     */
    public record TopologyLink(
        int endpointIndex,
        int coilIndex,
        List<Integer> switchIndices,
        boolean forwardPolarity
    ) {
        public TopologyLink {
            switchIndices = List.copyOf(switchIndices == null ? List.of() : switchIndices);
        }
    }

    public record CompiledPassive(
        ComponentId id,
        String name,
        double value
    ) {}
}
