package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Construction / property plumbing for the 3D preview. */
class EigenfieldPreviewCanvasTest {

    @Test
    void constructsWithNoScript() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            assertNotNull(preview);
            assertNull(preview.scriptProperty().get());
            preview.stop();
        });
    }

    @Test
    void acceptsCompiledScriptAndKeepsRunning() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            var script = EigenfieldScriptEngine.compile("return Vec3.of(0, 0, 1);");
            preview.scriptProperty().set(script);
            assertSame(script, preview.scriptProperty().get());
            // Touching a camera property should not throw
            preview.thetaProperty().set(1.2);
            preview.phiProperty().set(0.4);
            preview.zoomProperty().set(1.5);
            preview.stop();
        });
    }

    @Test
    void refreshResamplesWithoutThrowing() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            preview.scriptProperty().set(EigenfieldScriptEngine.compile(
                "return Vec3.of(sin(x), cos(y), z);"));
            preview.samplesPerAxisProperty().set(5);
            preview.halfExtentMProperty().set(0.2);
            preview.refresh();
            preview.stop();
        });
    }

    @Test
    void presetViewButtonsChangeAngles() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            preview.setPreset(0, Math.PI / 2);
            assertEquals(0.0, preview.thetaProperty().get(), 1e-12);
            assertEquals(Math.PI / 2, preview.phiProperty().get(), 1e-12);
            preview.resetView();
            assertEquals(0.6, preview.thetaProperty().get(), 1e-12);
            assertEquals(0.3, preview.phiProperty().get(), 1e-12);
            assertEquals(1.0, preview.zoomProperty().get(), 1e-12);
            preview.stop();
        });
    }

    @Test
    void handlesDivergentScriptWithoutCrashing() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            preview.scriptProperty().set((x, y, z) -> {
                // Return NaN occasionally — canvas should sanitise to zero.
                if (x == 0) return new Vec3(Double.NaN, 0, 0);
                return Vec3.ZERO;
            });
            preview.refresh();
            preview.stop();
        });
    }
}
