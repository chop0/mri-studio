package ax.xz.mri.ui.tutorial;

import ax.xz.mri.state.ProjectState;

import java.util.List;
import java.util.function.Predicate;

/**
 * A guided walkthrough of one studio task — e.g. "set up an NV adaptive
 * coherent project from scratch". Carries the ordered step list, a final
 * assertion the resulting {@link ProjectState} must satisfy (used by tests
 * and by the runner's done-detection), and display metadata for the Help
 * menu + welcome pane.
 *
 * <p>Tutorials are pure data — no per-tutorial logic. Step content drives
 * the runner; everything else (overlay rendering, UI subscriptions, menu
 * wiring) is generic.
 */
public record Tutorial(
    String id,
    String title,
    String description,
    List<TutorialStep> steps,
    Predicate<ProjectState> finalAssertion
) {
    public Tutorial {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (description == null) description = "";
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("steps must not be empty");
        steps = List.copyOf(steps);
        if (finalAssertion == null) finalAssertion = state -> true;
    }
}
