package ax.xz.mri.model.simulation.dsl;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.ScriptCompileException;
import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JDK-{@code javac}-backed eigenfield DSL engine.
 *
 * <p>Every source string is a full Java class implementing
 * {@link ax.xz.mri.dsl.EigenfieldScript} — default package, no class-level
 * modifiers — exactly as the user writes them in the editor. The
 * single-line {@code import module ax.xz.mri;} pulls in {@link Vec3} and
 * {@code EigenfieldScript} from the module.
 */
class EigenfieldScriptEngineTest {

    @BeforeEach
    void clearCache() {
        EigenfieldEngine.clearCache();
    }

    @Test
    void uniformBzReturnsConstant() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class UniformBz implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, 1);
                }
            }
            """);
        assertEquals(1.0, script.evaluate(0, 0, 0).z(), 1e-12);
        assertEquals(1.0, script.evaluate(0.1, 0.2, 0.3).z(), 1e-12);
    }

    @Test
    void mathStaticImportWorks() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            import static java.lang.Math.*;
            class WithMath implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    double r = sqrt(x*x + y*y);
                    return Vec3.of(sin(x), 0, cos(PI * r));
                }
            }
            """);
        var v = script.evaluate(0.5, 0, 0);
        assertEquals(Math.sin(0.5), v.x(), 1e-12);
        assertEquals(Math.cos(Math.PI * 0.5), v.z(), 1e-12);
    }

    @Test
    void gradientLinearInX() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class GradientX implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, x);
                }
            }
            """);
        assertEquals(0.05, script.evaluate(0.05, 0, 0).z(), 1e-12);
        assertEquals(-0.03, script.evaluate(-0.03, 1.0, -0.5).z(), 1e-12);
    }

    @Test
    void emptyScriptRejected() {
        assertThrows(ScriptCompileException.class, () -> EigenfieldEngine.compile("   "));
    }

    @Test
    void nullScriptRejected() {
        assertThrows(ScriptCompileException.class, () -> EigenfieldEngine.compile(null));
    }

    @Test
    void missingClassDeclarationRejected() {
        assertThrows(ScriptCompileException.class,
            () -> EigenfieldEngine.compile("return Vec3.of(0, 0, 1);"));
    }

    @Test
    void classDoesNotImplementInterfaceRejected() {
        assertThrows(ScriptCompileException.class,
            () -> EigenfieldEngine.compile("class NotAScript {}"));
    }

    @Test
    void syntaxErrorCarriesLocation() {
        var ex = assertThrows(ScriptCompileException.class,
            () -> EigenfieldEngine.compile("""
                import module ax.xz.mri;
                class BadSyntax implements EigenfieldScript {
                    public Vec3 evaluate(double x, double y, double z) {
                        return Vec3.of(0, 0, ;
                    }
                }
                """));
        assertTrue(ex.line() >= 1);
    }

    @Test
    void sameSourceCacheHit() {
        String src = """
            import module ax.xz.mri;
            class CacheHit implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, 1);
                }
            }
            """;
        assertSame(EigenfieldEngine.compile(src), EigenfieldEngine.compile(src));
    }

    @Test
    void starterLibraryScriptsAllCompileAndEvaluate() {
        for (var starter : EigenfieldStarterLibrary.all()) {
            var compiled = EigenfieldEngine.compile(starter.source());
            var v = compiled.evaluate(0, 0, 0);
            assertNotNull(v, "Starter " + starter.id() + " evaluated to null");
            assertTrue(Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()),
                "Starter " + starter.id() + " non-finite at origin");
        }
    }

    @Test
    void scriptSurvivesManyEvaluations() {
        var compiled = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            import static java.lang.Math.*;
            class ManyEvals implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(sin(x), cos(y), z*z);
                }
            }
            """);
        for (int i = 0; i < 5000; i++) {
            double v = i * 1e-4;
            var out = compiled.evaluate(v, v, v);
            assertTrue(Double.isFinite(out.magnitude()));
        }
    }

    @Test
    void helperMethodsAlongsideEvaluate() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class WithHelpers implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(scaledRadial(x), scaledRadial(y), 0);
                }
                static double scaledRadial(double v) { return 2.0 * v; }
            }
            """);
        var v = script.evaluate(1, 2, 3);
        assertEquals(new Vec3(2, 4, 0), v);
    }

    @Test
    void vec3HelperWorks() {
        var script = EigenfieldEngine.compile("""
            import module ax.xz.mri;
            class Scaled implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(x, y, z).scale(2.0);
                }
            }
            """);
        var v = script.evaluate(1, 2, 3);
        assertEquals(new Vec3(2, 4, 6), v);
    }
}
