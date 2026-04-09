package ax.xz.mri.service;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.simulation.ControlType;
import ax.xz.mri.model.simulation.EigenfieldPreset;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.List;
import java.util.UUID;

/**
 * Centralised creation logic for project objects.
 *
 * <p>This class has <b>no</b> hardcoded knowledge of specific MRI hardware.
 * Hardware-specific field definitions come from either:
 * <ul>
 *   <li>{@link #fieldsFromImport} — extracts from imported {@link FieldMap} data</li>
 *   <li>{@link ax.xz.mri.model.simulation.SimConfigTemplate} — user-chosen templates</li>
 * </ul>
 */
public final class ObjectFactory {
	private ObjectFactory() {}

	/** Tissue and spatial parameters (no field amplitudes — those belong to field definitions). */
	public record PhysicsParams(
		double gamma,
		double t1Ms, double t2Ms,
		double sliceHalfMm, double fovZMm, double fovRMm,
		int nZ, int nR
	) {
		public static final PhysicsParams DEFAULTS =
			new PhysicsParams(267.522e6, 1000, 100, 5, 20, 30, 50, 5);
	}

	// --- Generic builders (no hardware knowledge) ---

	/** Build a SimulationConfig from physics params and field definitions. */
	public static SimulationConfig buildConfig(PhysicsParams p, List<FieldDefinition> fields) {
		return new SimulationConfig(
			p.t1Ms, p.t2Ms, p.gamma,
			p.sliceHalfMm, p.fovZMm, p.fovRMm,
			Math.max(2, p.nZ), Math.max(2, p.nR),
			fields,
			List.of(
				new SimulationConfig.IsoPoint(0, 0, "Centre", "#e06000"),
				new SimulationConfig.IsoPoint(0, 2, "z = 2 mm", "#1976d2"),
				new SimulationConfig.IsoPoint(0, -2, "z = -2 mm", "#2e7d32")
			)
		);
	}

	/**
	 * Find an existing eigenfield with the same name and preset, or create a new one.
	 * Prevents duplication when multiple configs reference the same coil geometry.
	 */
	public static EigenfieldDocument findOrCreateEigenfield(
			ProjectRepository repo, String name, String description, EigenfieldPreset preset) {
		for (var id : repo.eigenfieldIds()) {
			var node = repo.node(id);
			if (node instanceof EigenfieldDocument ef && ef.name().equals(name) && ef.preset() == preset) {
				return ef;
			}
		}
		var eigen = new EigenfieldDocument(
			new ProjectNodeId("ef-" + UUID.randomUUID()), name, description, preset);
		repo.addEigenfield(eigen);
		return eigen;
	}

	// --- Import-specific extraction (hardware knowledge lives here) ---

	/** Extract tissue/spatial parameters from an imported FieldMap. */
	public static PhysicsParams extractFromFieldMap(FieldMap field) {
		if (field == null) return PhysicsParams.DEFAULTS;
		return new PhysicsParams(
			field.gamma != 0 ? field.gamma : 267.522e6,
			field.t1 * 1e3,
			field.t2 * 1e3,
			(field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3,
			field.fovZ * 1e3,
			field.fovX * 1e3,
			field.zMm != null ? field.zMm.length : 50,
			field.rMm != null ? field.rMm.length : 5
		);
	}

	/** Extract B0 field strength from an imported FieldMap. */
	public static double extractB0(FieldMap field) {
		return field != null ? field.b0n : 0.0154;
	}

	/**
	 * Build field definitions that match the imported hardware.
	 *
	 * <p>The imported BlochData comes from a specific MRI system with known coil
	 * geometry. This method creates eigenfields matching that hardware:
	 * <ul>
	 *   <li>B0 from a Helmholtz-like coil pair (Biot-Savart)</li>
	 *   <li>X-gradient from Golay coils</li>
	 *   <li>Z-gradient from a Maxwell pair</li>
	 *   <li>RF from a loop antenna (approximately uniform B1)</li>
	 * </ul>
	 */
	public static List<FieldDefinition> fieldsFromImport(FieldMap field, ProjectRepository repo) {
		double b0 = extractB0(field);
		double gamma = field != null && field.gamma != 0 ? field.gamma : PhysicsParams.DEFAULTS.gamma;

		var b0Eigen = findOrCreateEigenfield(repo,
			"B0 Helmholtz", "Main field from Helmholtz-like coils", EigenfieldPreset.BIOT_SAVART_HELMHOLTZ);
		var gxEigen = findOrCreateEigenfield(repo,
			"Gradient X (Golay)", "X-gradient from Golay coils", EigenfieldPreset.IDEAL_GRADIENT_X);
		var gzEigen = findOrCreateEigenfield(repo,
			"Gradient Z (Maxwell)", "Z-gradient from Maxwell pair", EigenfieldPreset.IDEAL_GRADIENT_Z);
		var rfEigen = findOrCreateEigenfield(repo,
			"RF Loop", "Transverse field from loop antenna", EigenfieldPreset.UNIFORM_B_PERP);

		double larmorHz = gamma * b0 / (2 * Math.PI);

		return List.of(
			new FieldDefinition("B0", ControlType.BINARY, 0, b0, 0, b0Eigen.id()),
			new FieldDefinition("Gradient X", ControlType.LINEAR, -0.030, 0.030, 0, gxEigen.id()),
			new FieldDefinition("Gradient Z", ControlType.LINEAR, -0.030, 0.030, 0, gzEigen.id()),
			new FieldDefinition("RF", ControlType.LINEAR, 0, 200e-6, larmorHz, rfEigen.id())
		);
	}
}
