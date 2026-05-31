package ax.xz.mri.ui.tutorial.tutorials;

import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.tutorial.AnchorKey;
import ax.xz.mri.ui.tutorial.Tutorial;
import ax.xz.mri.ui.tutorial.TutorialStep;
import ax.xz.mri.ui.workbench.CommandId;

import java.util.List;

/**
 * Tutorial that walks first-time users through building an NV-centre diamond
 * simulation + the adaptive-coherent procedure that reconstructs Bz(x) by
 * running iterated EKF updates against the simulator. End state:
 *
 * <ul>
 *   <li>1 simulation config (LOW field 10 mT, NV diamond template)</li>
 *   <li>4 eigenfields (B0 Helmholtz, Lorentzian sample dipole, MW transverse, Grad-X)</li>
 *   <li>1 NV-ensemble substance with ~16 centres in a 1 µm linear array</li>
 *   <li>1 circuit wired by the template (B0 + sample + MW + Grad-X coils,
 *       MW modulator, laser gate, optical counter)</li>
 *   <li>1 procedure document, source = {@code nv-adaptive-coherent} starter</li>
 * </ul>
 */
public final class NvCoherentTutorial {
    private NvCoherentTutorial() {}

    public static Tutorial build() {
        return new Tutorial(
            "nv-coherent",
            "NV adaptive coherent — Bz(x) reconstruction",
            "Sets up an NV-centre diamond simulation and the adaptive Bayesian "
            + "procedure that uses iterated Ramsey blocks to reconstruct the static "
            + "B-field along the sample's x-axis.",
            // Steps are project-state milestones: each advances when the
            // corresponding wizard commits a document. Mid-wizard guidance
            // (name it, pick the template, click Finish) lives in the bubble
            // body — the wizard doesn't touch ProjectState until Finish, so a
            // forward state-predicate can only key off the committed result.
            List.of(
                new TutorialStep(
                    AnchorKey.of(CommandId.NEW_SIM_CONFIG),
                    "Create the NV simulation",
                    "Open File ▸ New ▸ Simulation Config and choose the "
                    + "NV Centre Diamond template.",
                    state -> nvSubstanceInstalled(state)
                          && state.simulations().size() >= 1
                          && state.eigenfields().size() >= 4),
                new TutorialStep(
                    AnchorKey.of(CommandId.NEW_PROCEDURE),
                    "Add the adaptive procedure",
                    "Open File ▸ New ▸ Procedure and pick the "
                    + "NV adaptive (coherent) starter.",
                    state -> hasNvAdaptiveCoherentProcedure(state))
            ),
            // finalAssertion — covers the entire happy path.
            state -> state.simulations().size() == 1
                  && state.eigenfields().size() >= 4
                  && nvSubstanceInstalled(state)
                  && hasNvAdaptiveCoherentProcedure(state)
        );
    }

    /* ── Predicate helpers ────────────────────────────────────────────── */

    static boolean nvSubstanceInstalled(ProjectState state) {
        for (SubstanceDocument doc : state.substances().values()) {
            if (doc.substance() instanceof NvEnsemble) return true;
        }
        return false;
    }

    private static boolean hasNvAdaptiveCoherentProcedure(ProjectState state) {
        for (var doc : state.procedures().values()) {
            String src = doc.source();
            if (src != null && src.contains("class NvAdaptiveCoherent")) return true;
        }
        return false;
    }
}
