package ax.xz.mri.ui.framework;

import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the JavaFX texture-overflow crash that the editor
 * used to hit at very high zoom — {@code Requested texture dimensions
 * (18190x174) require dimensions (0x174) that exceed maximum texture
 * size (16384)}.
 *
 * <p>The cap lives on {@link ResizableCanvas#resize(double, double)}
 * which clamps both dimensions to {@link ResizableCanvas#MAX_TEXTURE_PX}.
 * If a parent layout asks the canvas to grow past that size the cap
 * silently truncates instead of letting JavaFX explode.
 */
class ResizableCanvasCapTest {

    @Test
    void resizeBeyondTextureCeilingClampsToCap() {
        FxTestSupport.runOnFxThread(() -> {
            var canvas = new ResizableCanvas();
            canvas.resize(50_000, 174);
            assertTrue(canvas.getWidth()  <= ResizableCanvas.MAX_TEXTURE_PX,
                "width must not exceed JavaFX texture ceiling");
            assertTrue(canvas.getHeight() <= ResizableCanvas.MAX_TEXTURE_PX,
                "height must not exceed JavaFX texture ceiling");
            assertEquals(174, canvas.getHeight());
        });
    }

    @Test
    void resizeWithinCapPassesThrough() {
        FxTestSupport.runOnFxThread(() -> {
            var canvas = new ResizableCanvas();
            canvas.resize(800, 600);
            assertEquals(800, canvas.getWidth());
            assertEquals(600, canvas.getHeight());
        });
    }

    @Test
    void resizeNegativeDimensionsClampedToZero() {
        FxTestSupport.runOnFxThread(() -> {
            var canvas = new ResizableCanvas();
            canvas.resize(-100, -10);
            assertEquals(0, canvas.getWidth());
            assertEquals(0, canvas.getHeight());
        });
    }

    @Test
    void maxTextureCeilingIsBelowJavaFxLimit() {
        // JavaFX's hardware ceiling is 16384 px; we cap at 16000 to leave headroom.
        assertTrue(ResizableCanvas.MAX_TEXTURE_PX <= 16384,
            "MAX_TEXTURE_PX must not exceed JavaFX's hardware texture ceiling");
        assertTrue(ResizableCanvas.MAX_TEXTURE_PX >= 4000,
            "MAX_TEXTURE_PX must be large enough for normal viewports");
    }
}
