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
            "Two anti-parallel point dipoles buried below the sample surface — " +
                "the canonical 'current loop pair' truth shape for NV magnetometry. " +
                "DEPTH sets how far the sources sit below z=0; SEPARATION is the " +
                "half-spacing along x. Normalised so |B|_peak ≈ 1 T at the NV layer " +
                "for a unit source drive — a Sample voltage source amplitude in " +
                "tesla then gives a peak field of that many tesla.",
            """
            import module ax.xz.mri;
            import static java.lang.Math.*;

            class LorentzianDipolePair implements EigenfieldScript {
                static final double DEPTH = 50e-9;        // dipole depth below surface
                static final double SEPARATION = 200e-9;  // half-separation along x

                /**
                 * Sample plane (in lab z): where NVs typically sit and where
                 * peak |Bz| is normalised to match the source amplitude.
                 * Default 50 nm matches the {@link NvArrayGeometry#depthMetres}
                 * default of the canonical "16-centre linear array" starter,
                 * so a Sample source set to A tesla produces a peak |Bz| of A
                 * tesla at the NV layer (not at the substrate surface).
                 */
                static final double SAMPLE_Z = 50e-9;

                /**
                 * Peak |Bz| of the raw (1/r³) dipole sum evaluated AT the NV
                 * plane (z = SAMPLE_Z). Used to renormalise so a Sample source
                 * amplitude of A gives a peak field of A tesla at the place the
                 * NVs actually sense, not at z=0 where there's nothing.
                 */
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
                    double zEffNv = SAMPLE_Z + DEPTH;   // z inside the dipole formula at the NV plane
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
