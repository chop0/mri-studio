package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.WizardStep;

/**
 * A named starter template shown in the new-sequence wizard.
 *
 * <p>Starters are UI-only affordances: they seed a new sequence with a
 * reasonable arrangement instead of starting from an empty timeline. Once
 * chosen, {@link #build} receives the active simulation config, resolved
 * circuit, and project state (so starters can resolve substance documents
 * and read γ for π/2-duration math); the starter's identity is not retained
 * in the data model.
 */
public interface SequenceStarter {
    String id();
    String name();
    String description();

    default WizardStep configStep() { return null; }

    /**
     * Build the initial {@link ClipSequence} for a sequence created against
     * {@code config} + {@code circuit}. {@code state} provides access to the
     * substance documents referenced by the circuit's substance blocks —
     * starters that need γ for pulse-duration math should resolve it through
     * the substance, never through a hardcoded proton constant.
     */
    ClipSequence build(SimulationConfig config, CircuitDocument circuit, ProjectState state);
}
