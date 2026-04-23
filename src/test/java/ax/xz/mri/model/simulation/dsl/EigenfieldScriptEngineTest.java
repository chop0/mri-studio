package ax.xz.mri.model.simulation.dsl;

import ax.xz.mri.model.simulation.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the Janino-backed eigenfield DSL engine. */
class EigenfieldScriptEngineTest {

    @BeforeEach
    void clearCache() {
        EigenfieldScriptEngine.clearCache();
    }

    @Test
    void uniformBzReturnsConstant() {
        var script = EigenfieldScriptEngine.compile("return Vec3.of(0, 0, 1);");
        assertEquals(1.0, script.evaluate(0, 0, 0).z(), 1e-12);
        assertEquals(1.0, script.evaluate(0.1, 0.2, 0.3).z(), 1e-12);
    }

    @Test
    void mathImportsAreAvailable() {
        var script = EigenfieldScriptEngine.compile("""
            double r = sqrt(x*x + y*y);
            return Vec3.of(sin(x), 0, cos(PI * r));
            """);
        var v = script.evaluate(0.5, 0, 0);
        assertEquals(Math.sin(0.5), v.x(), 1e-12);
        assertEquals(Math.cos(Math.PI * 0.5), v.z(), 1e-12);
    }

    @Test
    void gradientLinearInX() {
        var script = EigenfieldScriptEngine.compile("return Vec3.of(0, 0, x);");
        assertEquals(0.05, script.evaluate(0.05, 0, 0).z(), 1e-12);
        assertEquals(-0.03, script.evaluate(-0.03, 1.0, -0.5).z(), 1e-12);
    }

    @Test
    void emptyScriptRejected() {
        assertThrows(ScriptCompileException.class, () -> EigenfieldScriptEngine.compile("   "));
    }

    @Test
    void nullScriptRejected() {
        assertThrows(ScriptCompileException.class, () -> EigenfieldScriptEngine.compile(null));
    }

    @Test
    void syntaxErrorCarriesLocation() {
        var ex = assertThrows(ScriptCompileException.class,
            () -> EigenfieldScriptEngine.compile("return Vec3.of(0, 0, ;"));
        assertTrue(ex.line() >= 1);
    }

    @Test
    void typeErrorRejectedAtCompileTime() {
        assertThrows(ScriptCompileException.class,
            () -> EigenfieldScriptEngine.compile("return 42;"));
    }

    @Test
    void sameSourceCacheHit() {
        String src = "return Vec3.of(0, 0, 1);";
        assertSame(EigenfieldScriptEngine.compile(src), EigenfieldScriptEngine.compile(src));
    }

    @Test
    void starterLibraryScriptsAllCompileAndEvaluate() {
        for (var starter : EigenfieldStarterLibrary.all()) {
            var compiled = EigenfieldScriptEngine.compile(starter.source());
            var v = compiled.evaluate(0, 0, 0);
            assertNotNull(v, "Starter " + starter.id() + " evaluated to null");
            assertTrue(Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()),
                "Starter " + starter.id() + " non-finite at origin");
        }
    }

    @Test
    void scriptSurvivesManyEvaluations() {
        var compiled = EigenfieldScriptEngine.compile("return Vec3.of(sin(x), cos(y), z*z);");
        for (int i = 0; i < 5000; i++) {
            double v = i * 1e-4;
            var out = compiled.evaluate(v, v, v);
            assertTrue(Double.isFinite(out.magnitude()));
        }
    }

    @Test
    void vec3HelperWorks() {
        var script = EigenfieldScriptEngine.compile("return Vec3.of(x, y, z).scale(2.0);");
        var v = script.evaluate(1, 2, 3);
        assertEquals(new Vec3(2, 4, 6), v);
    }
}
