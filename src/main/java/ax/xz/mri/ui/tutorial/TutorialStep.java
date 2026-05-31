package ax.xz.mri.ui.tutorial;

import ax.xz.mri.state.ProjectState;

import java.util.function.Predicate;

/**
 * One coached action in a {@link Tutorial}.
 *
 * <p>A step has a target ({@link AnchorKey} — which control to spotlight),
 * a title + body for the bubble, and a {@link Predicate} that fires
 * {@code true} once the user has done what was asked. The {@link TutorialRunner}
 * watches {@code ProjectState} changes; when {@code completedWhen} returns
 * true, it advances to the next step.
 *
 * <p>Steps are pure forward: there's no "deviation" detection. If the user
 * goes off-script the step simply doesn't advance — the bubble stays put,
 * the spotlight stays on the original target. Cancel via the bubble's close
 * button (or the Help menu) and restart.
 */
public record TutorialStep(
    AnchorKey anchor,
    String title,
    String body,
    Predicate<ProjectState> completedWhen
) {
    public TutorialStep {
        if (anchor == null) throw new IllegalArgumentException("anchor must not be null");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (body == null) body = "";
        if (completedWhen == null) throw new IllegalArgumentException("completedWhen must not be null");
    }
}
