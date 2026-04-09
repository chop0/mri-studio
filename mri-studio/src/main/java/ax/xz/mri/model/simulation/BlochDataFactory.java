package ax.xz.mri.model.simulation;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Constructs a synthetic {@link BlochData} from a {@link SimulationConfig}
 * and a list of time segments (from a baked sequence).
 *
 * <p>This allows running Bloch simulations without importing real MRI data —
 * the field map, relaxation parameters, and initial magnetisation are all
 * generated from the config's physics, spatial, and field source definitions.
 */
public final class BlochDataFactory {
    private BlochDataFactory() {}

    /**
     * Build a complete BlochData from a simulation config and baked segments.
     *
     * @param config     the simulation environment parameters
     * @param segments   the baked time segments from the clip editor
     * @param repository the project repository (for resolving eigenfield presets)
     * @return a synthetic BlochData ready for Bloch simulation
     */
    public static BlochData build(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        var field = buildFieldMap(config, segments, repository);
        var iso = buildIsochromats(config);
        return new BlochData(field, iso, Map.of());
    }

    /**
     * Build BlochData without a repository (eigenfield presets resolved from field definitions only).
     * Used when no repository context is available — eigenfield presets default to UNIFORM.
     */
    public static BlochData build(SimulationConfig config, List<Segment> segments) {
        return build(config, segments, null);
    }

    private static FieldMap buildFieldMap(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        var field = new FieldMap();

        // Spatial grid
        int nR = Math.max(2, config.nR());
        int nZ = Math.max(2, config.nZ());

        field.rMm = new double[nR];
        field.zMm = new double[nZ];
        for (int i = 0; i < nR; i++) {
            field.rMm[i] = i * config.fovRMm() / (nR - 1);
        }
        for (int i = 0; i < nZ; i++) {
            field.zMm[i] = -config.fovZMm() / 2 + i * config.fovZMm() / (nZ - 1);
        }

        // Extract B0 from field definitions
        double b0Tesla = config.b0Tesla();
        field.b0n = b0Tesla;
        field.gamma = config.gamma();
        field.t1 = config.t1Ms() * 1e-3;
        field.t2 = config.t2Ms() * 1e-3;
        field.fovX = config.fovRMm() * 1e-3;
        field.fovZ = config.fovZMm() * 1e-3;
        field.sliceHalf = config.sliceHalfMm() * 1e-3;
        field.segments = segments;

        // Off-resonance map dBz[r][z] in μT — generated from B0 eigenfield preset
        field.dBzUt = new double[nR][nZ];
        EigenfieldPreset b0Preset = resolveB0Preset(config, repository);
        if (b0Preset == EigenfieldPreset.BIOT_SAVART_HELMHOLTZ) {
            // Biot-Savart computation would go here; for now, field is treated as uniform
            // (the FieldInterpolator already applies curvature corrections)
        }
        // UNIFORM_BZ and other presets: dBz stays at 0

        // Initial magnetisation: thermal equilibrium (Mz = 1)
        field.mx0 = new double[nR][nZ];
        field.my0 = new double[nR][nZ];
        field.mz0 = new double[nR][nZ];
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                field.mz0[ri][zi] = 1.0;
            }
        }

        return field;
    }

    /**
     * Resolve the B0 eigenfield preset from the field definitions.
     * Finds the first BINARY DC field and looks up its eigenfield preset.
     */
    private static EigenfieldPreset resolveB0Preset(SimulationConfig config, ProjectRepository repository) {
        if (repository == null) return EigenfieldPreset.UNIFORM_BZ;
        for (var fieldDef : config.fields()) {
            if (fieldDef.controlType() == ControlType.BINARY && fieldDef.isDC()) {
                var node = repository.node(fieldDef.eigenfieldId());
                if (node instanceof EigenfieldDocument eigen) {
                    return eigen.preset();
                }
            }
        }
        return EigenfieldPreset.UNIFORM_BZ;
    }

    private static List<BlochData.IsochromatDef> buildIsochromats(SimulationConfig config) {
        var iso = new ArrayList<BlochData.IsochromatDef>();
        for (var pt : config.isochromats()) {
            boolean inSlice = Math.abs(pt.zMm()) <= config.sliceHalfMm();
            iso.add(new BlochData.IsochromatDef(pt.name(), pt.colour(), inSlice));
        }
        return iso;
    }
}
