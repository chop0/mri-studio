package ax.xz.mri.ui.framework;

import ax.xz.mri.state.AppState;
import javafx.scene.layout.BorderPane;

/**
 * Abstract base for every dockable panel.
 * Subclasses extend either this directly (for form-based panes) or {@link CanvasPane}.
 */
public abstract class StudioPane extends BorderPane {
    protected final AppState appState;

    protected StudioPane(AppState appState) {
        this.appState = appState;
    }

    /** Unique identifier used by the layout manager. */
    public abstract String getPaneId();

    /** Human-readable title shown in the title bar. */
    public abstract String getPaneTitle();

    /** Called when the pane is added to the scene graph. Bind listeners here. */
    protected void onAttached() {}

    /** Called when the pane is removed. Unbind listeners here. */
    protected void onDetached() {}
}
