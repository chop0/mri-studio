package ax.xz.mri.project;

import ax.xz.mri.model.simulation.SimulationConfig;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Project-owned editable simulation configuration.
 *
 * <p>Each config is associated with a sequence and defines the physical
 * environment (B0, T1, T2, field geometry, isochromats) for live simulation
 * preview in the sequence editor.
 */
public record SimulationConfigDocument(
    ProjectNodeId id,
    String name,
    @JsonProperty("sequence_id") ProjectNodeId sequenceId,
    SimulationConfig config
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SIMULATION;
    }

    public SimulationConfigDocument withConfig(SimulationConfig newConfig) {
        return new SimulationConfigDocument(id, name, sequenceId, newConfig);
    }

    public SimulationConfigDocument withName(String newName) {
        return new SimulationConfigDocument(id, newName, sequenceId, config);
    }
}
