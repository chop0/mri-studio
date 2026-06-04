package ax.xz.mri.model.procedure;

import ax.xz.mri.project.ProcedureDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Built-in starter procedures shown in the New-Procedure wizard.
 *
 * <p>Each starter is real, compile-checked Java in the {@code :starters} Gradle
 * module ({@code starters/src/main/java/ax/xz/mri/starters/}), type-checked
 * against {@code ax.xz.mri}'s exported DSL surface on every build. The root
 * build's {@code copyStarterSources} task strips the module {@code package}
 * line and copies the sources into this module's resources, where the library
 * loads them lazily by classpath name — no giant inlined text blocks, and a
 * renamed API breaks the build rather than a user's run.
 *
 * <p>The starter source IS the implementation — there is no parallel Java
 * service class behind it. Editing the starter copies its source verbatim
 * into the new {@link ProcedureDocument} and the user takes it from there.
 */
public final class ProcedureStarterLibrary {
    private ProcedureStarterLibrary() {}

    /** Classpath directory holding the starter .java files. */
    private static final String STARTER_RESOURCE_DIR = "/ax/xz/mri/starters/";

    private static final List<ProcedureStarter> STARTERS = List.of(
        new ProcedureStarter(
            "blank",
            "Blank script",
            "Empty script — drop in your own loop / one-shot logic.",
            loadResource("Blank.java")),

        new ProcedureStarter(
            "nv-adaptive-coherent",
            "NV adaptive (coherent)",
            "Adaptive 2-phase tau + I-optimal action selection + iterated-EKF + Lorentzian GP "
            + "prior over Bz(x). Coherent readout (deterministic M observable given action). "
            + "Mirrors the Python adaptive_gradient_1d.py protocol; full self-contained port of "
            + "NvAdaptiveEstimator.",
            loadResource("NvAdaptiveCoherent.java")),

        new ProcedureStarter(
            "nv-kspace-sweep",
            "NV k-space sweep",
            "Sweep gradient Q over a fixed range, measure Var(M) per Q. The peak in "
            + "Var(M) lands at Q = +/- k_p of any random-phase spatial sinusoid in the sample "
            + "field. Self-contained port of NvKSpaceScanner.",
            loadResource("NvKSpaceSweep.java")),

        new ProcedureStarter(
            "pulse-optimisation",
            "Pulse optimisation (L-BFGS-B)",
            "L-BFGS-B optimisation of an N-segment hard-pulse train against a target final "
            + "magnetisation. Self-contained port of LbfgsbSolver + a small Bloch propagator; "
            + "finite-difference gradients.",
            loadResource("PulseOptimisation.java")),

        new ProcedureStarter(
            "mri-iterative-recon",
            "MRI iterative reconstruction",
            "Stub for SENSE / compressed-sensing reconstruction over a recorded probe trace.",
            loadResource("MriIterativeRecon.java"))
    );

    public static List<ProcedureStarter> all() { return STARTERS; }

    public static Optional<ProcedureStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static ProcedureStarter defaultStarter() {
        return STARTERS.stream().filter(s -> s.id().equals("blank")).findFirst().orElseThrow();
    }

    /**
     * Read a starter source from the classpath. Failures are unrecoverable —
     * a missing resource here means the jar was built without
     * {@code processResources} or the file was renamed without updating the
     * library, neither of which a runtime fallback could fix.
     */
    private static String loadResource(String fileName) {
        var path = STARTER_RESOURCE_DIR + fileName;
        try (InputStream in = ProcedureStarterLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing starter source resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load starter source: " + path, ex);
        }
    }
}
