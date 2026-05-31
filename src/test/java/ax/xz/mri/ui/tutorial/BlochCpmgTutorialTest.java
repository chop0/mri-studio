package ax.xz.mri.ui.tutorial;

import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.wizard.starters.SequenceStarterLibrary;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.ui.workbench.CommandId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the low-field MRI + CPMG tutorial end-to-end against a real shell.
 * Same shape as {@link NvCoherentTutorialTest}: each milestone performs the
 * wizard's committing action and the test asserts advancement, live anchors,
 * and final project shape.
 */
final class BlochCpmgTutorialTest {

    @Test
    void walkthroughBuildsBlochCpmgProject() {
        FxTestSupport.runOnFxThread(() -> {
            var pb = TutorialPlaybook.boot();
            try {
                var tutorial = TutorialLibrary.BLOCH_CPMG;
                pb.runner.start(tutorial);

                assertEquals(0, pb.currentStep(), "tutorial starts on step 0");
                assertTrue(pb.anchorResolves(AnchorKey.of(CommandId.NEW_SIM_CONFIG)),
                    "step 0 must spotlight a live New-Simulation-Config target");

                var config = pb.project.createSimConfig("Low-field MRI",
                    SimConfigTemplate.LOW_FIELD_MRI,
                    SimConfigTemplate.LOW_FIELD_MRI.defaultPhysics());

                assertEquals(1, pb.currentStep(), "creating the MRI config advances to step 1");
                assertTrue(pb.anchorResolves(AnchorKey.of(CommandId.NEW_SEQUENCE)),
                    "step 1 must spotlight a live New-Sequence target");

                // Finishing the sequence wizard with the CPMG starter, bound to
                // the config we just made.
                var cpmg = SequenceStarterLibrary.byId("cpmg").orElseThrow();
                pb.project.createSequenceFromStarter("CPMG", config.id(), cpmg);

                assertTrue(pb.runner.isComplete(), "creating the sequence completes the tutorial");

                var state = pb.state();
                assertTrue(tutorial.finalAssertion().test(state), "final assertion holds");
                assertEquals(1, state.simulations().size(), "exactly one simulation config");
                assertTrue(state.eigenfields().size() >= 4, "MRI template installs ≥ 4 eigenfields");
                assertEquals(1, state.sequences().size(), "exactly one sequence");
                long protonSubstances = state.substances().values().stream()
                    .filter(d -> d.substance() instanceof ContinuousMagnetisation).count();
                assertEquals(1, protonSubstances, "exactly one continuous-magnetisation substance");
            } finally {
                pb.dispose();
            }
        });
    }
}
