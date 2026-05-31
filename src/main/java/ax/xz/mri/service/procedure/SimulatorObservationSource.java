package ax.xz.mri.service.procedure;

import ax.xz.mri.dsl.BakedSequence;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.state.ProjectState;

/**
 * {@link ObservationSource} that compiles each requested sequence against a
 * {@link SimulationConfig} and runs the full multi-probe simulation.
 *
 * <p>The atomic-block reductions adaptive scripts rely on (one-step Rodrigues
 * rotation for the free-precession dt = τ block) happen <em>inside</em> the
 * NV kernel — the source itself doesn't switch paths or know which script is
 * calling. That's by design: the script is responsible for building a pulse
 * sequence the simulator can run efficiently, and the simulator is responsible
 * for running it. No bypass surface lives here.
 */
public record SimulatorObservationSource(
    SimulationConfig config,
    ProjectState repository
) implements ObservationSource {

    private static final SimulationCompiler COMPILER = new SimulationCompiler();

    public SimulatorObservationSource {
        if (config == null) throw new IllegalArgumentException("SimulatorObservationSource.config must be non-null");
    }

    @Override
    public String displayName() { return "Simulator"; }

    @Override
    public MultiProbeSignalTrace run(BakedSequence seq) {
        if (seq == null || seq.isEmpty()) return MultiProbeSignalTrace.empty();
        var sim = COMPILER.compile(config, seq.segments(), seq.pulses(), repository);
        return sim.runMultiProbe();
    }
}
