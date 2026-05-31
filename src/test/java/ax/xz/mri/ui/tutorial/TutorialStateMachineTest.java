package ax.xz.mri.ui.tutorial;

import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.workbench.CommandId;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-level test of {@link TutorialRunner}: a synthetic tutorial over a
 * controllable {@link ProjectState} property. Verifies the runner advances
 * one step per satisfied predicate, skips already-satisfied steps in one
 * tick, and reports completion — all without a real shell, wizard, or
 * rendered overlay (the overlay just hides when an anchor has no scene).
 */
final class TutorialStateMachineTest {

    /** Build a state carrying {@code n} procedures (cheap distinct states). */
    private static ProjectState withProcedures(int n) {
        var state = ProjectState.empty();
        for (int i = 0; i < n; i++) {
            state = state.withProcedure(new ProcedureDocument(
                new ProjectNodeId("proc-" + i), "P" + i,
                "class P" + i + " implements Script { public void run(ScriptContext c) {} }"));
        }
        return state;
    }

    private static Tutorial threeStepTutorial() {
        return new Tutorial("synthetic", "Synthetic", "three procedure milestones",
            List.of(
                new TutorialStep(AnchorKey.of(CommandId.NEW_PROCEDURE),
                    "one", "add a procedure", s -> s.procedures().size() >= 1),
                new TutorialStep(AnchorKey.of(CommandId.NEW_PROCEDURE),
                    "two", "add another", s -> s.procedures().size() >= 2),
                new TutorialStep(AnchorKey.of(CommandId.NEW_PROCEDURE),
                    "three", "add a third", s -> s.procedures().size() >= 3)),
            s -> s.procedures().size() >= 3);
    }

    @Test
    void advancesOneStepPerSatisfiedMilestone() {
        FxTestSupport.runOnFxThread(() -> {
            UiAnchors.clearForTest();
            var overlay = new TutorialOverlay();
            var stateProp = new SimpleObjectProperty<>(ProjectState.empty());
            var runner = new TutorialRunner(overlay, stateProp);

            runner.start(threeStepTutorial());
            assertEquals(0, runner.currentStepIndexProperty().get(), "starts on step 0");
            assertFalse(runner.isComplete());

            stateProp.set(withProcedures(1));
            assertEquals(1, runner.currentStepIndexProperty().get(), "advances after first milestone");

            stateProp.set(withProcedures(2));
            assertEquals(2, runner.currentStepIndexProperty().get(), "advances after second milestone");

            stateProp.set(withProcedures(3));
            assertTrue(runner.isComplete(), "complete once final predicate fires");

            runner.stop();
        });
    }

    @Test
    void skipsAlreadySatisfiedStepsInOneTick() {
        FxTestSupport.runOnFxThread(() -> {
            UiAnchors.clearForTest();
            var overlay = new TutorialOverlay();
            var stateProp = new SimpleObjectProperty<>(ProjectState.empty());
            var runner = new TutorialRunner(overlay, stateProp);

            runner.start(threeStepTutorial());
            // Jump straight to a state that satisfies all three predicates at
            // once — the runner should fast-forward through every step.
            stateProp.set(withProcedures(3));
            assertTrue(runner.isComplete(), "all milestones satisfied → complete in one tick");

            runner.stop();
        });
    }

    @Test
    void stopResetsToIdle() {
        FxTestSupport.runOnFxThread(() -> {
            UiAnchors.clearForTest();
            var overlay = new TutorialOverlay();
            var stateProp = new SimpleObjectProperty<>(ProjectState.empty());
            var runner = new TutorialRunner(overlay, stateProp);

            runner.start(threeStepTutorial());
            stateProp.set(withProcedures(1));
            assertEquals(1, runner.currentStepIndexProperty().get());

            runner.stop();
            assertEquals(-1, runner.currentStepIndexProperty().get(), "stop returns to idle");
            assertFalse(runner.isComplete());

            // After stop, further state changes don't advance anything.
            stateProp.set(withProcedures(2));
            assertEquals(-1, runner.currentStepIndexProperty().get(), "no advancement after stop");
        });
    }
}
