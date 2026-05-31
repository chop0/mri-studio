package ax.xz.mri.ui.wizard.starters;

import module ax.xz.mri;

import ax.xz.mri.model.circuit.starter.CircuitStarter;
import ax.xz.mri.model.circuit.starter.CircuitStarterLibrary;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.UUID;

/**
 * Named starting-point templates for new simulation configs.
 *
 * <p>Each template seeds a {@link CircuitDocument} into the repository and
 * points the new {@link SimulationConfig} at it. The simulation's spatial
 * layout lives on the substance documents referenced by the circuit; the
 * template just picks an integration step appropriate for the physics
 * scale (microseconds for MRI, nanoseconds for NV pulse work).
 */
public enum SimConfigTemplate {
    EMPTY("Empty", "Just a grounded blank schematic — build from scratch") {
        @Override public CircuitStarter circuitStarter() { return CircuitStarterLibrary.byId("empty").orElseThrow(); }
        @Override public double referenceB0Tesla() { return 1.5; }
        @Override public WizardStep configStep() { return null; }
        @Override public PhysicsParams defaultPhysics() { return PhysicsParams.DEFAULTS; }
    },
    LOW_FIELD_MRI("Standard low-field ¹H MRI",
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

        @Override public PhysicsParams defaultPhysics() { return PhysicsParams.DEFAULTS; }
    },
    NV_CENTRE_DIAMOND("NV centre diamond",
            "Linear NV array biased by a Helmholtz B0, sensing a buried dipole-pair sample. Low-field (10 mT) diamond regime.") {
        private NvDiamondConfigStep step;

        @Override public CircuitStarter circuitStarter() { return CircuitStarterLibrary.byId("nv-diamond").orElseThrow(); }

        /** Low-field diamond regime — 10 mT is comfortably above the ground-state-level-crossing at 102 mT/2. */
        @Override public double referenceB0Tesla() { return 0.01; }

        @Override
        public WizardStep configStep() {
            if (step == null) step = new NvDiamondConfigStep();
            return step;
        }

        @Override
        public NvSimulationMethod nvSimulationMethod() {
            configStep();                 // ensure the step exists
            return step.simulationMethod();
        }

        /** dt = 1 ns resolves NV Rabi pulses at ~MHz Rabi frequencies cleanly. */
        @Override public PhysicsParams defaultPhysics() { return new PhysicsParams(1e-9); }
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
     * The NV simulation technique this template configures. Defaults to the
     * fully-independent classical model; the NV-diamond template overrides it
     * from its {@link NvDiamondConfigStep}. Harmless (and ignored) for
     * templates with no NV substance.
     */
    public NvSimulationMethod nvSimulationMethod() { return NvSimulationMethod.independent(); }

    /** Default integration step the wizard's physics step opens with. */
    public abstract PhysicsParams defaultPhysics();

    /**
     * Build a fresh circuit document using this template's starter. The
     * caller is responsible for dispatching structural mutations for the
     * resulting circuit and any newly-minted eigenfields.
     */
    public CircuitStarter.Result buildCircuit(ProjectState state, String name) {
        var starter = circuitStarter();
        var id = new ProjectNodeId("circuit-" + UUID.randomUUID());
        return starter.build(id, name, state);
    }

    @Override
    public String toString() { return displayName; }
}
