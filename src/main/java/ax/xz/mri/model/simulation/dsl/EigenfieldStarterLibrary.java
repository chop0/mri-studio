package ax.xz.mri.model.simulation.dsl;

import java.util.List;
import java.util.Optional;

/**
 * Built-in starter eigenfield scripts shown in the New-Eigenfield wizard.
 *
 * <p>Each starter is a full Java class implementing
 * {@link ax.xz.mri.dsl.EigenfieldScript} — default package, no class-level
 * modifiers, helper methods alongside {@code evaluate}. They pull in
 * {@link ax.xz.mri.model.simulation.Vec3} and {@link ax.xz.mri.dsl.EigenfieldScript}
 * via a single {@code import module ax.xz.mri;} declaration; the
 * {@link ax.xz.mri.dsl.EigenfieldEngine} hands the source to the JDK
 * compiler unchanged.
 *
 * <p>The project file records only the user's final source, never which
 * starter (if any) was used.
 */
public final class EigenfieldStarterLibrary {
    private EigenfieldStarterLibrary() {}

    private static final List<EigenfieldStarter> STARTERS = List.of(
        new EigenfieldStarter(
            "blank",
            "Blank",
            "Zero field everywhere — start from scratch.",
            """
            import module ax.xz.mri;

            class Blank implements EigenfieldScript {
                // (x, y, z) are in metres. Amplitude scales the return value.
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.ZERO;
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "uniform-bz",
            "Uniform Bz",
            "Perfectly homogeneous z-directed field. The textbook B0.",
            """
            import module ax.xz.mri;

            class UniformBz implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, 1);
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "helmholtz-b0",
            "Helmholtz B0",
            "Realistic B0 from a Helmholtz-like coil pair. Unit at isocentre.",
            """
            import module ax.xz.mri;
            import static java.lang.Math.*;

            class HelmholtzB0 implements EigenfieldScript {
                static final double R = 0.10;   // coil radius (m)
                static final double D = R / 2;  // half-separation
                static final double PEAK = 1.0 / pow(1 + (D / R) * (D / R), 1.5);

                public Vec3 evaluate(double x, double y, double z) {
                    double u1 = (z - D) / R;
                    double u2 = (z + D) / R;
                    double bz0 = 0.5 / pow(1 + u1 * u1, 1.5)
                               + 0.5 / pow(1 + u2 * u2, 1.5);
                    double r = hypot(x, y);
                    double rho2 = (r / R) * (r / R);
                    double curvature = -0.5 * rho2 * (
                          (2.0 * u1 * u1 - 1.0) / pow(1 + u1 * u1, 3.5)
                        + (2.0 * u2 * u2 - 1.0) / pow(1 + u2 * u2, 3.5)
                    );
                    return Vec3.of(0, 0, (bz0 + curvature) / PEAK);
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "helmholtz-b0-shim",
            "B0 Helmholtz (shim)",
            "Helmholtz B0 with a small residual linear-z inhomogeneity (~2 mT/m "
            + "at 15 mT) — the static field imperfection a CPMG echo train refocuses, "
            + "so dephasing and rephasing are visible without an applied gradient.",
            """
            import module ax.xz.mri;
            import static java.lang.Math.*;

            class HelmholtzB0Shim implements EigenfieldScript {
                static final double R = 0.10;   // coil radius (m)
                static final double D = R / 2;  // half-separation
                static final double PEAK = 1.0 / pow(1 + (D / R) * (D / R), 1.5);
                // Residual z-shim, in fractions of B0 per metre. At B0 = 15.4 mT
                // this is ~2 mT/m: the FID dephases (T2*) within a ~1 ms echo
                // spacing and the 180° pulses refocus it into clear echoes.
                static final double SHIM_PER_M = 0.13;

                public Vec3 evaluate(double x, double y, double z) {
                    double u1 = (z - D) / R;
                    double u2 = (z + D) / R;
                    double bz0 = 0.5 / pow(1 + u1 * u1, 1.5)
                               + 0.5 / pow(1 + u2 * u2, 1.5);
                    double r = hypot(x, y);
                    double rho2 = (r / R) * (r / R);
                    double curvature = -0.5 * rho2 * (
                          (2.0 * u1 * u1 - 1.0) / pow(1 + u1 * u1, 3.5)
                        + (2.0 * u2 * u2 - 1.0) / pow(1 + u2 * u2, 3.5)
                    );
                    // Shim added after normalisation, so isocentre (z=0) stays unit.
                    return Vec3.of(0, 0, (bz0 + curvature) / PEAK + SHIM_PER_M * z);
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "gradient-x",
            "Gradient X",
            "Linear x-gradient of Bz. At 1 T/m, Bz(x) = x tesla.",
            """
            import module ax.xz.mri;

            class GradientX implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, x);
                }
            }
            """,
            "T/m"),

        new EigenfieldStarter(
            "gradient-z",
            "Gradient Z",
            "Linear z-gradient of Bz. At 1 T/m, Bz(z) = z tesla.",
            """
            import module ax.xz.mri;

            class GradientZ implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(0, 0, z);
                }
            }
            """,
            "T/m"),

        new EigenfieldStarter(
            "uniform-b-perp",
            "Uniform B⊥",
            "Perfectly uniform transverse (x-directed) B1. Ideal RF coil.",
            """
            import module ax.xz.mri;

            class UniformBPerp implements EigenfieldScript {
                // Pair two REAL sources (I, Q) through a Modulator at the Larmor
                // carrier to drive a coil that uses this eigenfield.
                public Vec3 evaluate(double x, double y, double z) {
                    return Vec3.of(1, 0, 0);
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "surface-loop-rx",
            "Surface loop B⊥",
            "Surface receive loop on the +x side with exponential depth falloff.",
            """
            import module ax.xz.mri;
            import static java.lang.Math.*;

            class SurfaceLoop implements EigenfieldScript {
                static final double R0 = 0.05;
                public Vec3 evaluate(double x, double y, double z) {
                    double depth = R0 - x;
                    double lateral = hypot(y, z);
                    double depthFall = exp(-max(depth, 0) / R0);
                    double radialFall = exp(-lateral * lateral / (R0 * R0));
                    return Vec3.of(depthFall * radialFall, 0, 0);
                }
            }
            """,
            "T"),

        new EigenfieldStarter(
            "lorentzian-dipole",
            "Lorentzian dipole pair",
            "Anti-parallel buried-dipole pair, modelled (as in the reference NV " +
                "magnetometry literature) by a difference of Lorentzians along x: " +
                "Bz(x) = z²·(1/(z²+(x−SEP)²) − 1/(z²+(x+SEP)²)). HALFWIDTH z sets " +
                "how sharply the field varies; SEP is the half-spacing. A Sample " +
                "source amplitude A tesla reproduces this with peak |Bz| ≈ 0.985·A " +
                "at x = ±SEP. (This is exactly the truth field B_true(x) of the " +
                "adaptive_gradient_1d notebook with B_amp = A.)",
            """
            import module ax.xz.mri;
            import static java.lang.Math.*;

            class LorentzianDipolePair implements EigenfieldScript {
                static final double HALFWIDTH = 50e-9;    // z: Lorentzian half-width along x
                static final double SEPARATION = 200e-9;  // half-spacing of the dipole pair

                // Difference of two Lorentzians centred at ±SEPARATION. Purely a
                // function of x (the buried-source field is taken at the fixed NV
                // plane), so the NV depth doesn't enter — matching the notebook's
                // 1-D B_true(x). A Sample drive of A tesla scales this directly.
                public Vec3 evaluate(double x, double y, double z) {
                    double z2 = HALFWIDTH * HALFWIDTH;
                    double xm = x - SEPARATION, xp = x + SEPARATION;
                    double bz = z2 * (1.0 / (z2 + xm * xm) - 1.0 / (z2 + xp * xp));
                    return Vec3.of(0, 0, bz);
                }
            }
            """,
            "T")
    );

    public static List<EigenfieldStarter> all() {
        return STARTERS;
    }

    public static Optional<EigenfieldStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static EigenfieldStarter defaultStarter() {
        return STARTERS.stream().filter(s -> s.id().equals("uniform-bz")).findFirst().orElseThrow();
    }
}
