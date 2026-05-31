package ax.xz.mri.ui.sim;

import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.state.ProjectState;

/**
 * One simulation submission — a frozen snapshot of the sequence document, the
 * active config, the project repository (used for circuit lookup), and the
 * generation token captured at submit time. {@link SimRunner} consumes this on
 * its worker thread, computes a {@link SimResult} and only publishes it back
 * if the generation is still current.
 */
public record SimRequest(
    String configName,
    SequenceDocument sequence,
    SimulationConfig config,
    ProjectState repository,
    long generation
) {}
