package ax.xz.mri.model.sequence;

import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.ui.wizard.WizardStep;

/**
 * A named starter template shown in the New-Sequence wizard.
 *
 * <p>Starters are UI-only affordances: they exist so users can seed a new
 * sequence with a reasonable arrangement instead of starting from an empty
 * timeline. Once chosen, {@link #build(SimulationConfig)} is called with the
 * active {@link SimulationConfig} to produce the concrete {@link ClipSequence};
 * the starter's identity is not retained anywhere in the data model.
 *
 * <p>A starter is free to inspect the config (field kinds, amplitudes, gamma)
 * and tailor its clips so the seeded sequence is physically meaningful against
 * that config. Starters that need user customisation (echo counts, timing,
 * etc.) return a non-null {@link #configStep()}; the wizard inserts that step
 * after starter selection and reads the values back through
 * {@link #build(SimulationConfig)}.
 */
public interface SequenceStarter {
    String id();
    String name();
    String description();

    /**
     * Customisation step shown in the wizard after the starter is chosen, or
     * {@code null} if the starter has no options to configure. The wizard
     * treats this the same way {@code SimConfigTemplate.configStep()} works:
     * a delegating step swaps the returned step's content in/out.
     */
    default WizardStep configStep() { return null; }

    /** Build the initial {@link ClipSequence} for a sequence created against {@code config}. */
    ClipSequence build(SimulationConfig config);
}
