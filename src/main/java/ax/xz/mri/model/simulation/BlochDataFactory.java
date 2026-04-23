package ax.xz.mri.model.simulation;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CircuitCompiler;

import java.util.List;

/**
 * Builds a runtime {@link FieldMap} from a {@link SimulationConfig} and its
 * {@linkplain SimulationConfig#circuitId() circuit}.
 *
 * <p>The factory resolves the circuit through the project repository and
 * compiles it against the (r, z) grid. Runtime per-step values — including
 * STATIC source contributions to Bz — come out of the MNA solve. The only
 * constant baked into {@code staticBz} is the rotating-frame reference offset
 * {@code −B0ref}; everything else is dynamic.
 */
public final class BlochDataFactory {
    private BlochDataFactory() {}

    public static BlochData build(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        return new BlochData(buildFieldMap(config, segments, repository));
    }

    public static BlochData build(SimulationConfig config, List<Segment> segments) {
        return build(config, segments, null);
    }

    private static FieldMap buildFieldMap(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        var field = new FieldMap();
        int nR = Math.max(2, config.nR());
        int nZ = Math.max(2, config.nZ());
        field.rMm = new double[nR];
        field.zMm = new double[nZ];
        for (int i = 0; i < nR; i++) field.rMm[i] = i * config.fovRMm() / (nR - 1);
        for (int i = 0; i < nZ; i++) field.zMm[i] = -config.fovZMm() / 2 + i * config.fovZMm() / (nZ - 1);

        field.b0Ref = config.referenceB0Tesla();
        field.gamma = config.gamma();
        field.t1 = config.t1Ms() * 1e-3;
        field.t2 = config.t2Ms() * 1e-3;
        field.fovX = config.fovRMm() * 1e-3;
        field.fovZ = config.fovZMm() * 1e-3;
        field.sliceHalf = config.sliceHalfMm() * 1e-3;
        field.segments = segments;

        field.mx0 = new double[nR][nZ];
        field.my0 = new double[nR][nZ];
        field.mz0 = new double[nR][nZ];
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                field.mz0[ri][zi] = 1.0;
            }
        }

        CircuitDocument circuit = resolveCircuit(config, repository);
        field.circuit = CircuitCompiler.compile(circuit, repository, field.rMm, field.zMm);

        // The only bake: the rotating-frame reference offset. STATIC sources
        // flow through the MNA every step, so they contribute their Bz
        // dynamically via coil eigenfields; we don't fold them into the grid.
        double[][] staticBz = new double[nR][nZ];
        double refOffset = -config.referenceB0Tesla();
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) staticBz[ri][zi] = refOffset;
        }
        field.staticBz = staticBz;

        return field;
    }

    private static CircuitDocument resolveCircuit(SimulationConfig config, ProjectRepository repository) {
        if (repository == null || config.circuitId() == null) return null;
        return repository.node(config.circuitId()) instanceof CircuitDocument c ? c : null;
    }
}
