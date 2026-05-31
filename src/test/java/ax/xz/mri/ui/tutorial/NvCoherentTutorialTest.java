package ax.xz.mri.ui.tutorial;

import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.ui.workbench.CommandId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the NV adaptive coherent tutorial end-to-end against a real shell.
 * For each milestone the test performs the wizard's committing action (the
 * same {@code project.createX} call the wizard's resultFactory fires on
 * Finish) and asserts the runner advances, the step's spotlight target
 * resolves to a live node, and the final project matches the tutorial's
 * declared shape.
 */
final class NvCoherentTutorialTest {

    @Test
    void walkthroughBuildsNvCoherentProject() {
        FxTestSupport.runOnFxThread(() -> {
            var pb = TutorialPlaybook.boot();
            try {
                var tutorial = TutorialLibrary.NV_COHERENT;
                pb.runner.start(tutorial);

                // Step 1 — spotlight points at the New ▸ Simulation Config command.
                assertEquals(0, pb.currentStep(), "tutorial starts on step 0");
                assertTrue(pb.anchorResolves(AnchorKey.of(CommandId.NEW_SIM_CONFIG)),
                    "step 0 must spotlight a live New-Simulation-Config target");

                // Perform what finishing the sim-config wizard does.
                pb.project.createSimConfig("NV diamond",
                    SimConfigTemplate.NV_CENTRE_DIAMOND,
                    SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics());

                assertEquals(1, pb.currentStep(), "creating the NV config advances to step 1");
                assertTrue(pb.anchorResolves(AnchorKey.of(CommandId.NEW_PROCEDURE)),
                    "step 1 must spotlight a live New-Procedure target");

                // Perform what finishing the procedure wizard does.
                var src = ProcedureStarterLibrary.byId("nv-adaptive-coherent").orElseThrow().source();
                pb.project.createProcedure("NV adaptive coherent", src);

                assertTrue(pb.runner.isComplete(), "creating the procedure completes the tutorial");

                // Final project shape.
                var state = pb.state();
                assertTrue(tutorial.finalAssertion().test(state), "final assertion holds");
                assertEquals(1, state.simulations().size(), "exactly one simulation config");
                assertTrue(state.eigenfields().size() >= 4, "NV template installs ≥ 4 eigenfields");
                assertEquals(1, state.procedures().size(), "exactly one procedure");
                long nvSubstances = state.substances().values().stream()
                    .filter(d -> d.substance() instanceof NvEnsemble).count();
                assertEquals(1, nvSubstances, "exactly one NV-ensemble substance");
            } finally {
                pb.dispose();
            }
        });
    }
}
