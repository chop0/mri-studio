package ax.xz.mri.ui.tutorial.tutorials;

import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.tutorial.AnchorKey;
import ax.xz.mri.ui.tutorial.Tutorial;
import ax.xz.mri.ui.tutorial.TutorialStep;
import ax.xz.mri.ui.workbench.CommandId;

import java.util.List;

/**
 * Tutorial that walks first-time users through setting up a continuous-
 * magnetisation Bloch simulation + a CPMG T2 sequence. End state:
 *
 * <ul>
 *   <li>1 simulation config (LOW_FIELD_MRI template — ~15 mT B0)</li>
 *   <li>4 eigenfields (B0 Helmholtz, RF transverse, Gx, Gz)</li>
 *   <li>1 ContinuousMagnetisation substance (proton water grid)</li>
 *   <li>1 circuit (B0 + RF + Gx + Gz coils, RF modulator, T/R mux, probe)</li>
 *   <li>1 sequence document from the {@code cpmg} starter</li>
 * </ul>
 *
 * <p>Final assertion verifies the sequence resolves γ from a ContinuousMagnetisation
 * substance — that's what the cpmg starter requires.
 */
public final class BlochCpmgTutorial {
    private BlochCpmgTutorial() {}

    public static Tutorial build() {
        return new Tutorial(
            "bloch-cpmg",
            "Low-field MRI + CPMG sequence",
            "Sets up a proton MRI simulation on a ~15 mT Helmholtz pair and a "
            + "CPMG echo train for T2 measurement.",
            // Project-state milestones; in-wizard guidance lives in the body.
            List.of(
                new TutorialStep(
                    AnchorKey.of(CommandId.NEW_SIM_CONFIG),
                    "Create the MRI simulation",
                    "Open File ▸ New ▸ Simulation Config and choose the "
                    + "Low-field MRI template.",
                    state -> blochSubstanceInstalled(state)
                          && state.simulations().size() >= 1
                          && state.eigenfields().size() >= 4),
                new TutorialStep(
                    AnchorKey.of(CommandId.NEW_SEQUENCE),
                    "Build a CPMG echo train",
                    "Open File ▸ New ▸ Sequence and pick the CPMG starter.",
                    // Advances on any sequence — we guide toward CPMG in the
                    // body but don't hard-gate on the sequence's name, which
                    // the user is free to change.
                    state -> !state.sequences().isEmpty())
            ),
            state -> state.simulations().size() == 1
                  && state.eigenfields().size() >= 4
                  && blochSubstanceInstalled(state)
                  && state.sequences().size() == 1
        );
    }

    /* ── Predicate helpers ────────────────────────────────────────────── */

    static boolean blochSubstanceInstalled(ProjectState state) {
        for (SubstanceDocument doc : state.substances().values()) {
            if (doc.substance() instanceof ContinuousMagnetisation) return true;
        }
        return false;
    }
}
