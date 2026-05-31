package ax.xz.mri.ui.framework;

import javafx.scene.canvas.Canvas;

/**
 * A Canvas that follows its parent's size and exposes a resize callback.
 *
 * <p>JavaFX manages backing-buffer scaling internally, so this class stays
 * honest about what it does instead of pretending to implement custom HiDPI
 * logic.
 *
 * <p>JavaFX's hardware texture ceiling is 16384 px on the longer side; a
 * Canvas asked to grow past that throws a {@code RuntimeException} from
 * {@code NGCanvas.initCanvas}, blanking the whole render. We cap each
 * dimension at {@link #MAX_TEXTURE_PX} so a misbehaving parent never crashes
 * the editor — painters use {@link #getWidth()} / {@link #getHeight()} as
 * their truth, so the cap simply means the painter draws into the visible
 * portion at the cap, which is always at least as wide as the viewport.
 */
public class ResizableCanvas extends Canvas {
    /** Hard cap matching JavaFX's hardware texture ceiling. */
    public static final double MAX_TEXTURE_PX = 16000;

    private Runnable onResized;

    public ResizableCanvas() { super(1, 1); }

    @Override public boolean isResizable()  { return true; }
    @Override public double  minWidth(double h)  { return 1; }
    @Override public double  minHeight(double w) { return 1; }
    @Override public double  maxWidth(double h)  { return MAX_TEXTURE_PX; }
    @Override public double  maxHeight(double w) { return MAX_TEXTURE_PX; }

    @Override
    public void resize(double width, double height) {
        double w = Math.min(Math.max(0, width),  MAX_TEXTURE_PX);
        double h = Math.min(Math.max(0, height), MAX_TEXTURE_PX);
        setWidth(w);
        setHeight(h);
        if (onResized != null) onResized.run();
    }

    public void setOnResized(Runnable r) { this.onResized = r; }
}
