package ax.xz.mri.model.simulation;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Constructs a synthetic {@link BlochData} from a {@link SimulationConfig}
 * and a list of time segments (from a baked sequence).
 *
 * <p>This allows running Bloch simulations without importing real MRI data —
 * the field map, relaxation parameters, and initial magnetisation are all
 * generated from the config's physics and spatial parameters.
 */
public final class BlochDataFactory {
    private BlochDataFactory() {}

    /**
     * Build a complete BlochData from a simulation config and baked segments.
     *
     * @param config   the simulation environment parameters
     * @param segments the baked time segments from the clip editor
     * @return a synthetic BlochData ready for Bloch simulation
     */
    public static BlochData build(SimulationConfig config, List<Segment> segments) {
        var field = buildFieldMap(config, segments);
        var iso = buildIsochromats(config);
        // No scenarios — this is a direct simulation, not an imported dataset
        return new BlochData(field, iso, Map.of());
    }

    private static FieldMap buildFieldMap(SimulationConfig config, List<Segment> segments) {
        var field = new FieldMap();

        // Spatial grid — always at least 2 points per dimension for valid interpolation
        int nR = Math.max(2, config.nR());
        int nZ = Math.max(2, config.nZ());

        if (config.preset() == FieldPreset.SINGLE_POINT) {
            nR = 2;
            nZ = 2;
        }

        field.rMm = new double[nR];
        field.zMm = new double[nZ];
        for (int i = 0; i < nR; i++) {
            field.rMm[i] = i * config.fovRMm() / (nR - 1);  // 0 to fovR (radial is non-negative)
        }
        for (int i = 0; i < nZ; i++) {
            field.zMm[i] = -config.fovZMm() / 2 + i * config.fovZMm() / (nZ - 1);
        }

        // Field parameters
        field.b0n = config.b0Tesla();
        field.gamma = config.gamma();
        field.t1 = config.t1Ms() * 1e-3;  // ms → seconds (simulator uses seconds)
        field.t2 = config.t2Ms() * 1e-3;  // ms → seconds
        field.fovX = config.fovRMm() * 1e-3;
        field.fovZ = config.fovZMm() * 1e-3;
        field.sliceHalf = config.sliceHalfMm() * 1e-3;
        field.segments = segments;

        // Off-resonance map dBz[r][z] in μT
        field.dBzUt = new double[nR][nZ];
        if (config.preset() == FieldPreset.LINEAR_GRADIENT) {
            for (int ri = 0; ri < nR; ri++) {
                for (int zi = 0; zi < nZ; zi++) {
                    field.dBzUt[ri][zi] = config.dBzLinearUtPerMm() * field.zMm[zi];
                }
            }
        }
        // UNIFORM and SINGLE_POINT: dBz stays at 0

        // Initial magnetisation: equilibrium (Mz = 1)
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

    private static List<BlochData.IsochromatDef> buildIsochromats(SimulationConfig config) {
        var iso = new ArrayList<BlochData.IsochromatDef>();
        for (var pt : config.isochromats()) {
            boolean inSlice = Math.abs(pt.zMm()) <= config.sliceHalfMm();
            iso.add(new BlochData.IsochromatDef(pt.name(), pt.colour(), inSlice));
        }
        return iso;
    }
}
