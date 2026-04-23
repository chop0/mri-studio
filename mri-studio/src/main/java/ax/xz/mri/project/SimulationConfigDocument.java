package ax.xz.mri.project;

import ax.xz.mri.model.simulation.SimulationConfig;

/**
 * Project-owned editable simulation configuration.
 *
 * <p>Defines the physical environment (B0, T1, T2, field geometry, field sources,
 * receive coils, simulation time step) for live simulation preview. The
 * association between a sequence and its config is stored on the
 * {@link SequenceDocument#activeSimConfigId()}, NOT here.
 */
public record SimulationConfigDocument(
    ProjectNodeId id,
    String name,
    SimulationConfig config
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SIMULATION_CONFIG;
    }

    public SimulationConfigDocument withConfig(SimulationConfig newConfig) {
        return new SimulationConfigDocument(id, name, newConfig);
    }

    public SimulationConfigDocument withName(String newName) {
        return new SimulationConfigDocument(id, newName, config);
    }
}
