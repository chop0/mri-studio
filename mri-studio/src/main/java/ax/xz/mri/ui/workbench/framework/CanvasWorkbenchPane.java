package ax.xz.mri.ui.workbench.framework;

import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.workbench.PaneContext;
import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

/** Shared single-canvas pane base with tracked redraw subscriptions. */
public abstract class CanvasWorkbenchPane extends WorkbenchPane {
    protected final ResizableCanvas canvas = new ResizableCanvas();
    private final List<Runnable> disposers = new ArrayList<>();
    private ContextMenu activeContextMenu;
    private boolean dirty;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!dirty) return;
            dirty = false;
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            if (width > 0 && height > 0) {
                paint(canvas.getGraphicsContext2D(), width, height);
            }
        }
    };

    protected CanvasWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneContent(new StackPane(canvas));
        canvas.setOnResized(this::scheduleRedraw);
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            paneContext.activate();
            if (activeContextMenu != null && activeContextMenu.isShowing() && event.isPrimaryButtonDown()) {
                activeContextMenu.hide();
            }
        });
        timer.start();
    }

    protected final void bindRedraw(Observable... observables) {
        var listener = (InvalidationListener) obs -> scheduleRedraw();
        for (var observable : observables) {
            observable.addListener(listener);
            disposers.add(() -> observable.removeListener(listener));
        }
        scheduleRedraw();
    }

    protected final void scheduleRedraw() {
        dirty = true;
    }

    protected final void showCanvasContextMenu(ContextMenu menu, double screenX, double screenY) {
        if (activeContextMenu != null) activeContextMenu.hide();
        activeContextMenu = menu;
        menu.setAutoHide(true);
        menu.show(canvas, screenX, screenY);
    }

    @Override
    public void dispose() {
        timer.stop();
        disposers.forEach(Runnable::run);
        disposers.clear();
    }

    protected abstract void paint(GraphicsContext graphics, double width, double height);
}
