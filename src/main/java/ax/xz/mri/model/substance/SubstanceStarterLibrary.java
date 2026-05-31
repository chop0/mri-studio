package ax.xz.mri.model.substance;

import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvPhysics;

import java.util.List;
import java.util.Optional;

/**
 * Built-in starter substances shown in the New-Substance wizard.
 *
 * <p>Two canonical scenarios cover the supported substance subtypes:
 *
 * <ul>
 *   <li><b>Continuous water (¹H)</b> — bulk proton magnetisation with
 *       textbook T₁/T₂ and γ. The everyday MRI starting point.</li>
 *   <li><b>Bulk-diamond NV (16-centre linear array)</b> — a 16-NV linear
 *       array along x at 50 nm depth. The smallest layout that exercises
 *       the cluster-tier compile path without saturating it.</li>
 * </ul>
 */
public final class SubstanceStarterLibrary {

    private SubstanceStarterLibrary() {}

    private static final List<SubstanceStarter> STARTERS = List.of(
        new SubstanceStarter(
            "continuous-water-h1",
            "Continuous water (¹H)",
            "Bulk proton magnetisation — T₁ = 1.0 s, T₂ = 0.1 s, γ = 2.675e8 rad/s/T, "
            + "m_z0 = 1.0, sampled at 5 × 5 × 50 voxels over ±30 mm × ±30 mm × ±10 mm.",
            ContinuousMagnetisation.defaults()
        ),

        new SubstanceStarter(
            "nv-ensemble-linear-16",
            "Bulk-diamond NV (16-centre linear array)",
            "16 NV centres uniformly spaced over 1 µm at 50 nm depth, axis [+z]. "
            + "NV physics at defaults; how centres couple is a simulation-method choice.",
            new NvEnsemble(
                new NvArrayGeometry(NvArrayShape.LINEAR_X_UNIFORM, 16, 1e-6, 50e-9,
                    NvAxis.AXIS_PLUS_Z, 0L),
                NvPhysics.defaults(),
                0L
            )
        )
    );

    public static List<SubstanceStarter> all() { return STARTERS; }

    public static Optional<SubstanceStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static SubstanceStarter defaultStarter() {
        return byId("continuous-water-h1").orElseThrow();
    }
}
