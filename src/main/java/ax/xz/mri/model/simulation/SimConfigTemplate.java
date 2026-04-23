package ax.xz.mri.model.simulation;

import ax.xz.mri.model.circuit.starter.CircuitStarter;
import ax.xz.mri.model.circuit.starter.CircuitStarterLibrary;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.UUID;

/**
 * Named starting-point templates for new simulation configs.
 *
 * <p>Each template seeds a {@link ax.xz.mri.model.circuit.CircuitDocument}
 * into the repository and points the new {@link SimulationConfig} at it.
 * Field-level physics knobs (tissue, grid, reference frame) come from the
 * user's wizard input; the circuit handles sources / switches / coils /
 * probes.
 */
public enum SimConfigTemplate {
    EMPTY("Empty", "Just a grounded blank schematic \u2014 build from scratch") {
        @Override public CircuitStarter circuitStarter() { return CircuitStarterLibrary.byId("empty").orElseThrow(); }
        @Override public double referenceB0Tesla() { return 1.5; }
        @Override public WizardStep configStep() { return null; }
    },
    LOW_FIELD_MRI("Standard low-field \u00b9H MRI",
            "B0 + Gx + Gz + RF TX + RX probe through a T/R switch on a ~15 mT Helmholtz system") {
        private LowFieldMriConfigStep step;

        @Override public CircuitStarter circuitStarter() { return CircuitStarterLibrary.byId("low-field-mri").orElseThrow(); }

        @Override
        public double referenceB0Tesla() {
            return step != null ? step.getB0Tesla() : 0.0154;
        }

        @Override
        public WizardStep configStep() {
            if (step == null) step = new LowFieldMriConfigStep();
            return step;
        }
    };

    private final String displayName;
    private final String description;

    SimConfigTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }

    public abstract CircuitStarter circuitStarter();
    public abstract double referenceB0Tesla();
    public abstract WizardStep configStep();

    /**
     * Seed a fresh circuit into the repository via this template's starter,
     * and return its id so a {@link SimulationConfig} can point at it.
     */
    public ProjectNodeId createCircuit(ProjectRepository repository, String name) {
        var starter = circuitStarter();
        var id = new ProjectNodeId("circuit-" + UUID.randomUUID());
        var doc = starter.build(id, name, repository);
        repository.addCircuit(doc);
        return id;
    }

    @Override
    public String toString() { return displayName; }
}
