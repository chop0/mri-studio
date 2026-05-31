package ax.xz.mri.ui.tutorial;

import module javafx.controls;

import ax.xz.mri.state.ProjectState;
import javafx.beans.value.ChangeListener;

/**
 * Engine that drives a {@link Tutorial} through its steps.
 *
 * <p>The runner owns a single {@link TutorialOverlay} and an
 * {@link ObservableValue}-style subscription to the active project state.
 * On every state change, it re-evaluates the current step's
 * {@link TutorialStep#completedWhen} predicate; when true, the runner
 * advances. When the final step's predicate fires, the runner displays a
 * "done" message on the final anchor and stops.
 *
 * <p>If the registered {@link UiAnchors} entry for the current step's anchor
 * is absent (e.g. the wizard the step expects hasn't opened yet), the
 * overlay is hidden and the runner re-polls registration on the next state
 * change or anchor-registration event. This keeps the bubble from appearing
 * before the user has navigated to the right pane.
 */
public final class TutorialRunner {

    private final TutorialOverlay overlay;
    private final ReadOnlyObjectProperty<ProjectState> stateProperty;
    private final ReadOnlyIntegerWrapper currentStepIndex = new ReadOnlyIntegerWrapper(-1);

    private Tutorial tutorial;
    private ChangeListener<ProjectState> stateListener;
    private Timeline anchorPoller;

    // What the overlay is currently showing, so the 100 ms poller doesn't
    // re-issue showStep() every tick (which would restart the pulse anim).
    private int renderedStep = -1;
    private Node renderedTarget;

    public TutorialRunner(TutorialOverlay overlay, ReadOnlyObjectProperty<ProjectState> stateProperty) {
        if (overlay == null) throw new IllegalArgumentException("overlay must not be null");
        if (stateProperty == null) throw new IllegalArgumentException("stateProperty must not be null");
        this.overlay = overlay;
        this.stateProperty = stateProperty;
    }

    /** Start a tutorial from step 0. Replaces any in-progress tutorial. */
    public void start(Tutorial t) {
        if (t == null) throw new IllegalArgumentException("tutorial must not be null");
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> start(t));
            return;
        }
        stop();
        this.tutorial = t;
        currentStepIndex.set(0);
        renderedStep = -1;
        renderedTarget = null;
        attachStateListener();
        startAnchorPoller();
        evaluateAndRender();
    }

    /** Cancel the current tutorial; the overlay disappears. */
    public void stop() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::stop);
            return;
        }
        detachStateListener();
        stopAnchorPoller();
        overlay.hide();
        tutorial = null;
        currentStepIndex.set(-1);
        renderedStep = -1;
        renderedTarget = null;
    }

    /** {@code true} once the final step's predicate has fired. */
    public boolean isComplete() {
        return tutorial != null
            && currentStepIndex.get() >= tutorial.steps().size();
    }

    /** Read-only: which step is active (0-indexed), or -1 when no tutorial is running. */
    public ReadOnlyIntegerProperty currentStepIndexProperty() { return currentStepIndex.getReadOnlyProperty(); }

    /** The currently-running tutorial, or {@code null} if none. */
    public Tutorial activeTutorial() { return tutorial; }

    /* ── Internal state-machine ────────────────────────────────────────── */

    private void attachStateListener() {
        if (stateListener != null) return;
        stateListener = (obs, o, n) -> evaluateAndRender();
        stateProperty.addListener(stateListener);
    }

    private void detachStateListener() {
        if (stateListener != null) stateProperty.removeListener(stateListener);
        stateListener = null;
    }

    /**
     * Anchor registration happens asynchronously (a wizard's buttons get
     * registered when the wizard's modal stage opens). Re-poll every 100 ms
     * so the overlay re-renders when the missing anchor finally appears.
     * Cheap (only runs while a tutorial is active).
     */
    private void startAnchorPoller() {
        anchorPoller = new Timeline(
            new KeyFrame(Duration.millis(100), e -> evaluateAndRender()));
        anchorPoller.setCycleCount(Animation.INDEFINITE);
        anchorPoller.play();
    }

    private void stopAnchorPoller() {
        if (anchorPoller != null) anchorPoller.stop();
        anchorPoller = null;
    }

    /**
     * Re-check the current step's predicate against the latest state, advance
     * if satisfied, and repaint the overlay against the current step's
     * anchor. Idempotent — safe to call repeatedly.
     */
    private void evaluateAndRender() {
        if (tutorial == null) return;
        ProjectState state = stateProperty.get();
        int idx = currentStepIndex.get();
        // Advance through any steps that are already satisfied (handles the
        // case where a state change satisfies the predicate while we're
        // waiting for the anchor to register).
        while (idx >= 0 && idx < tutorial.steps().size()
            && tutorial.steps().get(idx).completedWhen().test(state)) {
            idx++;
            currentStepIndex.set(idx);
        }
        if (idx >= tutorial.steps().size()) {
            overlay.hide();
            stopAnchorPoller();
            detachStateListener();
            renderedStep = -1;
            renderedTarget = null;
            return;
        }
        var step = tutorial.steps().get(idx);
        Node target = UiAnchors.lookup(step.anchor());
        if (target == null || target.getScene() == null) {
            // Anchor not mounted yet — hide and wait. Anchor poller will
            // re-fire and resume rendering when it appears.
            overlay.hide();
            renderedStep = -1;
            renderedTarget = null;
            return;
        }
        // Only (re)issue showStep when the step or its target actually
        // changed — otherwise the poller would restart the pulse animation
        // every 100 ms.
        if (idx != renderedStep || target != renderedTarget) {
            String stepText = "Step " + (idx + 1) + " of " + tutorial.steps().size();
            overlay.showStep(target, stepText, step.title(), step.body(), this::stop);
            renderedStep = idx;
            renderedTarget = target;
        }
    }
}
