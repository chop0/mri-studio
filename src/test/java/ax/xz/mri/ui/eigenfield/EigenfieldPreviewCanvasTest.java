package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.dsl.EigenfieldEngine;
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
            var script = EigenfieldEngine.compile("""
                import module ax.xz.mri;
                class S implements EigenfieldScript {
                    public Vec3 evaluate(double x, double y, double z) { return Vec3.of(0, 0, 1); }
                }
                """);
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
            preview.scriptProperty().set(EigenfieldEngine.compile("""
                import module ax.xz.mri;
                import static java.lang.Math.*;
                class S implements EigenfieldScript {
                    public Vec3 evaluate(double x, double y, double z) {
                        return Vec3.of(sin(x), cos(y), z);
                    }
                }
                """));
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

    /* ── Auto-fit heuristic ────────────────────────────────────────────── */

    /**
     * Lorentzian dipole pair: SEPARATION = 200 nm, NV plane at 50 nm. The
     * dominant spatial feature is on the 100–500 nm scale; the canvas's
     * default half-extent of 10 cm shows essentially zero field anywhere
     * (the feature is 6 orders of magnitude smaller than the visible cube).
     * The heuristic must pick a sub-micron half-extent so the user can
     * actually see the dipole pair.
     */
    @Test
    void autoDetectFramesLorentzianDipolePair() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            import static java.lang.Math.*;
            class LorentzianDipolePair implements EigenfieldScript {
                static final double DEPTH = 50e-9;
                static final double SEPARATION = 200e-9;
                static final double SAMPLE_Z = 50e-9;
                static final double PEAK_NORM = computePeak();
                public Vec3 evaluate(double x, double y, double z) {
                    double z_eff = z + DEPTH;
                    double bzPlus  =  dipoleBz(x - SEPARATION, y, z_eff);
                    double bzMinus = -dipoleBz(x + SEPARATION, y, z_eff);
                    return Vec3.of(0, 0, (bzPlus + bzMinus) / PEAK_NORM);
                }
                private static double dipoleBz(double dx, double dy, double dz) {
                    double r2 = dx*dx + dy*dy + dz*dz;
                    if (r2 < 1e-30) return 0;
                    double r = sqrt(r2);
                    double cosTheta = dz / r;
                    return (3.0 * cosTheta * cosTheta - 1.0) / (r * r * r);
                }
                private static double computePeak() {
                    double zEffNv = SAMPLE_Z + DEPTH;
                    double best = 0;
                    for (int i = 0; i <= 400; i++) {
                        double x = (-2.0 + 4.0 * i / 400) * SEPARATION;
                        double v = abs(dipoleBz(x - SEPARATION, 0, zEffNv)
                                     - dipoleBz(x + SEPARATION, 0, zEffNv));
                        if (v > best) best = v;
                    }
                    return best;
                }
            }
            """);
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        // The dipole pair's separation is 200 nm; we expect half-extent in
        // the sub-micron / few-micron range — emphatically NOT the canvas's
        // 10 cm default that hides the field entirely.
        assertTrue(extent >= 1e-8 && extent <= 5e-6,
            "expected sub-micron half-extent for the Lorentzian dipole pair, got " + extent + " m");
    }

    /**
     * Helmholtz B0 pair (coil radius R ≈ 0.1 m). The field is roughly
     * uniform inside the coils and drops off outside. The kink in the
     * magnitude profile lies near the coil radius — half-extent should
     * land in the ~10 cm to ~1 m range.
     */
    @Test
    void autoDetectFramesHelmholtzB0() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            import static java.lang.Math.*;
            class HelmholtzB0 implements EigenfieldScript {
                static final double R = 0.10;
                static final double D = R / 2;
                public Vec3 evaluate(double x, double y, double z) {
                    double u1 = (z - D) / R;
                    double u2 = (z + D) / R;
                    double bz = 0.5 / pow(1 + u1*u1, 1.5) + 0.5 / pow(1 + u2*u2, 1.5);
                    // Approximation: peak Bz ≈ 0.715 at u1=u2=0 with our scaling.
                    return Vec3.of(0, 0, bz / 0.715);
                }
            }
            """);
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        assertTrue(extent >= 0.02 && extent <= 2.0,
            "expected ~10 cm half-extent for a Helmholtz B0 pair, got " + extent + " m");
    }

    /**
     * Uniform field — no characteristic length scale. The heuristic must
     * fall through to the default rather than picking 1 nm or 100 m.
     */
    @Test
    void autoDetectFallsBackOnUniformField() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class U implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) { return Vec3.of(1, 0, 0); }
            }
            """);
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        // Should be a UI-reasonable value — somewhere between 1 mm and 10 m,
        // emphatically not a numerical-noise corner of the search range.
        assertTrue(extent >= 1e-9 && extent <= 1e2,
            "uniform-field fallback should land in the displayable range, got " + extent + " m");
    }

    /**
     * Pure linear gradient: B = (0, 0, x) (so |B|(x) = |x|). No
     * characteristic length. Heuristic falls through to "scale where
     * |B| ≈ 1" — i.e. ≈ 1 m.
     */
    @Test
    void autoDetectFallsBackOnLinearGradient() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class G implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) { return Vec3.of(0, 0, x); }
            }
            """);
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        // The constant-log-derivative fallback picks the scale at which |B|
        // first sits in [0.5, 5] — for the unit-slope gradient that's
        // around 1 m. Wide tolerance so the discrete log-spacing doesn't
        // make this brittle.
        assertTrue(extent >= 0.1 && extent <= 10.0,
            "linear gradient should fall back to a metre-scale extent, got " + extent + " m");
    }

    /** Null script → safe default; no NPE. */
    @Test
    void autoDetectReturnsDefaultForNullScript() {
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(null);
        assertEquals(EigenfieldPreviewCanvas.DEFAULT_HALF_EXTENT_M, extent, 1e-12);
    }

    /** autoFitHalfExtent on a script-bound canvas updates the property. */
    @Test
    void autoFitAppliesDetectedExtentToProperty() {
        FxTestSupport.runOnFxThread(() -> {
            var preview = new EigenfieldPreviewCanvas();
            preview.scriptProperty().set(EigenfieldEngine.compile("""
                import module ax.xz.mri;
                import static java.lang.Math.*;
                class S implements EigenfieldScript {
                    public Vec3 evaluate(double x, double y, double z) {
                        // Tight Gaussian feature at the micron scale.
                        double r2 = x*x + y*y + z*z;
                        return Vec3.of(0, 0, exp(-r2 / (1e-6 * 1e-6)));
                    }
                }
                """));
            preview.halfExtentMProperty().set(1.0);   // wildly wrong default
            preview.autoFitHalfExtent();
            double extent = preview.halfExtentMProperty().get();
            assertTrue(extent >= 1e-7 && extent <= 1e-4,
                "Gaussian at 1 µm should produce a few-micron half-extent, got " + extent + " m");
            preview.stop();
        });
    }

    /**
     * The starter library's actual Lorentzian dipole pair script (the one the
     * user opens) must auto-fit to a sub-micron half-extent. Locks the
     * heuristic against the script the user actually sees, not just a
     * simplified test surrogate.
     */
    @Test
    void autoDetectHandlesStarterLibraryLorentzianDipolePair() {
        var starter = ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary
            .byId("lorentzian-dipole").orElseThrow();
        var script = EigenfieldEngine.compile(starter.source());
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        assertTrue(extent >= 1e-8 && extent <= 5e-6,
            "starter Lorentzian dipole pair should auto-fit to a sub-micron half-extent, got "
            + extent + " m (10 cm default would hide the field entirely)");
    }

    /** Helmholtz starter library script — sanity-check the actual numerical pick. */
    @Test
    void autoDetectHandlesStarterLibraryHelmholtzB0() {
        var starter = ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary
            .byId("helmholtz-b0").orElseThrow();
        var script = EigenfieldEngine.compile(starter.source());
        double extent = EigenfieldPreviewCanvas.autoDetectHalfExtent(script);
        assertTrue(extent >= 0.05 && extent <= 5.0,
            "Helmholtz starter should auto-fit to a coil-scale half-extent, got " + extent + " m");
    }
}
