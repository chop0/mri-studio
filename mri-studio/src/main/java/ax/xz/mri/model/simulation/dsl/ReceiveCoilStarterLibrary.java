package ax.xz.mri.model.simulation.dsl;

import java.util.List;
import java.util.Optional;

/**
 * Built-in starter receive-coil templates for new simulation configs.
 *
 * <p>A starter pairs a user-visible name and description with (a) the
 * eigenfield starter to seed the coil's spatial sensitivity from, and
 * (b) default gain / phase. These are UI templates only — the project file
 * records only the final {@link ax.xz.mri.model.simulation.ReceiveCoil}.
 */
public final class ReceiveCoilStarterLibrary {
    private ReceiveCoilStarterLibrary() {}

    public record ReceiveCoilStarter(
        String id,
        String name,
        String description,
        String eigenfieldStarterId,
        double gain,
        double phaseDeg
    ) {}

    private static final List<ReceiveCoilStarter> STARTERS = List.of(
        new ReceiveCoilStarter(
            "uniform-isotropic",
            "Uniform isotropic",
            "Perfectly uniform transverse sensitivity — the idealised whole-volume receiver.",
            "uniform-b-perp",
            1.0, 0.0),
        new ReceiveCoilStarter(
            "helmholtz-reciprocal",
            "Helmholtz reciprocal",
            "Receive coil whose sensitivity mirrors a Helmholtz B0 transverse component.",
            "helmholtz-b0",
            1.0, 0.0),
        new ReceiveCoilStarter(
            "surface-loop",
            "Surface loop",
            "Single surface loop on +x with exponential depth falloff.",
            "surface-loop-rx",
            1.0, 0.0)
    );

    public static List<ReceiveCoilStarter> all() {
        return STARTERS;
    }

    public static Optional<ReceiveCoilStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static ReceiveCoilStarter defaultStarter() {
        return STARTERS.get(0);
    }
}
