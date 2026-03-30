package ax.xz.mri.ui.framework;

import javafx.scene.canvas.Canvas;
import javafx.stage.Screen;

/**
 * A Canvas that follows its parent's size and renders at the screen's native
 * DPI (Retina/HiDPI aware). The logical size for layout purposes matches the
 * parent, but the backing pixel buffer is scaled up for crisp rendering.
 */
public class ResizableCanvas extends Canvas {
    private Runnable onResized;

    public ResizableCanvas() { super(1, 1); }

    @Override public boolean isResizable()  { return true; }
    @Override public double  minWidth(double h)  { return 1; }
    @Override public double  minHeight(double w) { return 1; }
    @Override public double  maxWidth(double h)  { return Double.MAX_VALUE; }
    @Override public double  maxHeight(double w) { return Double.MAX_VALUE; }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        if (onResized != null) onResized.run();
    }

    public void setOnResized(Runnable r) { this.onResized = r; }
}
