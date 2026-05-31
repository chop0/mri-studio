package ax.xz.mri.ui.sim;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;

import java.util.List;

/**
 * The product of one {@link SimRequest}: the compiled simulation, the baked
 * pulse, and the pre-computed signal traces. Carries the originating
 * generation so late results can be discarded by anyone holding a
 * {@link ax.xz.mri.ui.time.Generation} reference.
 */
public record SimResult(
    CompiledSimulation simulation,
    List<PulseSegment> pulse,
    MultiProbeSignalTrace traces,
    long generation
) {}
