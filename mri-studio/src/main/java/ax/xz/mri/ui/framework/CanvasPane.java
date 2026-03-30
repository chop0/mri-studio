package ax.xz.mri.ui.framework;

import ax.xz.mri.state.AppState;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.StackPane;

/**
 * Base class for all canvas-rendering panes.
 * Subclasses declare their repaint triggers via {@link #getRedrawTriggers()} and
 * implement rendering in {@link #paint(GraphicsContext, double, double)}.
 * Repaints are coalesced: at most one per animation frame (60 fps).
 */
public abstract class CanvasPane extends StudioPane {
    protected final ResizableCanvas canvas = new ResizableCanvas();
    private volatile boolean dirty = false;

    /** Active context menu — stored so we can dismiss it on click-away. */
    protected ContextMenu activeContextMenu;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (dirty) {
                dirty = false;
                double w = canvas.getWidth(), h = canvas.getHeight();
                if (w > 0 && h > 0) paint(canvas.getGraphicsContext2D(), w, h);
            }
        }
    };

    protected CanvasPane(AppState appState) {
        super(appState);
        var wrapper = new StackPane(canvas);
        setCenter(wrapper);
        canvas.setOnResized(this::scheduleRedraw);
        installContextMenuDismissal();
        timer.start();
    }

    @Override
    protected void onAttached() {
        InvalidationListener l = obs -> scheduleRedraw();
        for (var obs : getRedrawTriggers()) obs.addListener(l);
        scheduleRedraw();
    }

    /** Declare all JavaFX Observables that should trigger a repaint. */
    protected abstract Observable[] getRedrawTriggers();

    /** Render the pane content onto {@code g}. Called on the FX thread. */
    protected abstract void paint(GraphicsContext g, double w, double h);

    /** Override to install mouse/key handlers after the canvas is created. */
    protected void installMouseHandlers() {}

    protected void scheduleRedraw() { dirty = true; }

    /** Show a context menu and track it for auto-dismissal. */
    protected void showContextMenu(ContextMenu menu, double screenX, double screenY) {
        if (activeContextMenu != null) activeContextMenu.hide();
        activeContextMenu = menu;
        menu.setAutoHide(true);
        menu.show(canvas, screenX, screenY);
    }

    /** Dismiss active context menu on any primary mouse press on the canvas. */
    private void installContextMenuDismissal() {
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (activeContextMenu != null && activeContextMenu.isShowing() && e.isPrimaryButtonDown()) {
                activeContextMenu.hide();
                activeContextMenu = null;
            }
        });
    }
}
