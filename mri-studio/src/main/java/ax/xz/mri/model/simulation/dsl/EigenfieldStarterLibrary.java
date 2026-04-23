package ax.xz.mri.model.simulation.dsl;

import java.util.List;
import java.util.Optional;

/**
 * Built-in starter eigenfield scripts shown in the New-Eigenfield wizard.
 *
 * <p>These are UI templates, not data-model values — the project file records
 * only the user's final script, never which starter (if any) was used to
 * create it. Users are free to modify or discard any of them.
 */
public final class EigenfieldStarterLibrary {
    private EigenfieldStarterLibrary() {}

    private static final List<EigenfieldStarter> STARTERS = List.of(
        new EigenfieldStarter(
            "blank",
            "Blank",
            "Zero field everywhere — start from scratch.",
            """
            // Zero field — edit to taste.
            // (x, y, z) are in metres. Amplitude scales the return value.
            return Vec3.ZERO;
            """,
            "T", 1.0),

        new EigenfieldStarter(
            "uniform-bz",
            "Uniform Bz",
            "Perfectly homogeneous z-directed field. The textbook B0.",
            """
            // Perfectly homogeneous z-directed field.
            return Vec3.of(0, 0, 1);
            """,
            "T", 1.0),

        new EigenfieldStarter(
            "helmholtz-b0",
            "Helmholtz B0",
            "Realistic B0 from a Helmholtz-like coil pair. Unit at isocentre.",
            """
            // Helmholtz coil pair (two loops of radius R at z = +/- R/2).
            // Unit-normalised at isocentre; leading radial curvature correction.
            double R = 0.10;                        // coil radius (m)
            double d = R / 2.0;                     // half-separation
            double u1 = (z - d) / R;
            double u2 = (z + d) / R;
            double bz0 = 0.5 / pow(1 + u1 * u1, 1.5)
                       + 0.5 / pow(1 + u2 * u2, 1.5);
            double r = hypot(x, y);
            double rho2 = (r / R) * (r / R);
            double curvature = -0.5 * rho2 * (
                  (2.0 * u1 * u1 - 1.0) / pow(1 + u1 * u1, 3.5)
                + (2.0 * u2 * u2 - 1.0) / pow(1 + u2 * u2, 3.5)
            );
            double peak = 1.0 / pow(1 + (d / R) * (d / R), 1.5);
            return Vec3.of(0, 0, (bz0 + curvature) / peak);
            """,
            "T", 1.0),

        new EigenfieldStarter(
            "gradient-x",
            "Gradient X",
            "Linear x-gradient of Bz. At 1 T/m, Bz(x) = x tesla.",
            """
            // Linear x-gradient of Bz.
            return Vec3.of(0, 0, x);
            """,
            "T/m", 1.0),

        new EigenfieldStarter(
            "gradient-z",
            "Gradient Z",
            "Linear z-gradient of Bz. At 1 T/m, Bz(z) = z tesla.",
            """
            // Linear z-gradient of Bz.
            return Vec3.of(0, 0, z);
            """,
            "T/m", 1.0),

        new EigenfieldStarter(
            "uniform-b-perp",
            "Uniform B\u22a5",
            "Perfectly uniform transverse (x-directed) B1. Ideal RF coil.",
            """
            // Uniform transverse B1 — ideal RF coil pointing along +x.
            // Pair with a quadrature (QUADRATURE) amplitude at the Larmor carrier.
            return Vec3.of(1, 0, 0);
            """,
            "T", 1.0),

        new EigenfieldStarter(
            "surface-loop-rx",
            "Surface loop B\u22a5",
            "Surface receive loop on the +x side with exponential depth falloff.",
            """
            // Surface loop centred at (+r0, 0, 0) facing -x. Transverse sensitivity
            // decays exponentially with depth into the FOV and falls off radially.
            double r0 = 0.05;
            double depth = r0 - x;
            double lateral = Math.hypot(y, z);
            double depthFall = Math.exp(-Math.max(depth, 0) / r0);
            double radialFall = Math.exp(-lateral * lateral / (r0 * r0));
            double amp = depthFall * radialFall;
            return Vec3.of(amp, 0, 0);
            """,
            "T", 1.0)
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
