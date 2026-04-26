package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.ui.wizard.WizardStep;

/**
 * A named starter template shown in the new-sequence wizard.
 *
 * <p>Starters are UI-only affordances: they seed a new sequence with a
 * reasonable arrangement instead of starting from an empty timeline. Once
 * chosen, {@link #build} receives the active simulation config and resolved
 * circuit; the starter's identity is not retained in the data model.
 */
public interface SequenceStarter {
    String id();
    String name();
    String description();

    default WizardStep configStep() { return null; }

    /** Build the initial {@link ClipSequence} for a sequence created against {@code config} + {@code circuit}. */
    ClipSequence build(SimulationConfig config, CircuitDocument circuit);
}
