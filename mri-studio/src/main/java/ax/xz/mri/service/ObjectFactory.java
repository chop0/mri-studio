package ax.xz.mri.service;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.ImportedFieldMap;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.List;
import java.util.UUID;

/**
 * Centralised creation logic for project objects.
 *
 * <p>Has no hardcoded knowledge of specific MRI hardware. Hardware-specific
 * field definitions come from either:
 * <ul>
 *   <li>{@link #fieldsFromImport} — maps the four legacy channels from an
 *       imported {@link ImportedFieldMap} into {@link FieldDefinition}s.</li>
 *   <li>{@link ax.xz.mri.model.simulation.SimConfigTemplate} — user-chosen
 *       templates backed by starter eigenfields.</li>
 * </ul>
 */
public final class ObjectFactory {
	private ObjectFactory() {}

	public record PhysicsParams(
		double gamma,
		double t1Ms, double t2Ms,
		double sliceHalfMm, double fovZMm, double fovRMm,
		int nZ, int nR,
		double dtSeconds
	) {
		public static final PhysicsParams DEFAULTS =
			new PhysicsParams(267.522e6, 1000, 100, 5, 20, 30, 50, 5, 1e-6);
	}

	public static SimulationConfig buildConfig(PhysicsParams p, double referenceB0Tesla, List<FieldDefinition> fields) {
		return new SimulationConfig(
			p.t1Ms, p.t2Ms, p.gamma,
			p.sliceHalfMm, p.fovZMm, p.fovRMm,
			Math.max(2, p.nZ), Math.max(2, p.nR),
			referenceB0Tesla,
			p.dtSeconds,
			fields
		);
	}

	/**
	 * Find an existing eigenfield with the same name and script, or create a new one.
	 * Prevents duplication when multiple configs reference the same coil geometry.
	 */
	public static EigenfieldDocument findOrCreateEigenfield(
			ProjectRepository repo, String name, String description, String script) {
		for (var id : repo.eigenfieldIds()) {
			var node = repo.node(id);
			if (node instanceof EigenfieldDocument ef && ef.name().equals(name) && ef.script().equals(script)) {
				return ef;
			}
		}
		var eigen = new EigenfieldDocument(
			new ProjectNodeId("ef-" + UUID.randomUUID()), name, description, script);
		repo.addEigenfield(eigen);
		return eigen;
	}

	// --- Import-specific extraction ---

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
			field.rMm != null ? field.rMm.length : 5,
			PhysicsParams.DEFAULTS.dtSeconds()
		);
	}

	public static double extractB0(FieldMap field) {
		return field != null ? field.b0Ref : 0.0154;
	}

	/**
	 * Build field definitions matching the imported hardware's four legacy
	 * channels (B0, Gx, Gz, RF) using starter eigenfield scripts.
	 */
	public static List<FieldDefinition> fieldsFromImport(FieldMap field, ProjectRepository repo) {
		double b0 = extractB0(field);
		double gamma = field != null && field.gamma != 0 ? field.gamma : PhysicsParams.DEFAULTS.gamma;

		var b0Starter = EigenfieldStarterLibrary.byId("helmholtz-b0").orElseThrow();
		var gxStarter = EigenfieldStarterLibrary.byId("gradient-x").orElseThrow();
		var gzStarter = EigenfieldStarterLibrary.byId("gradient-z").orElseThrow();
		var rfStarter = EigenfieldStarterLibrary.byId("uniform-b-perp").orElseThrow();

		var b0Eigen = findOrCreateEigenfield(repo, "B0 Helmholtz", b0Starter.description(), b0Starter.source());
		var gxEigen = findOrCreateEigenfield(repo, "Gradient X (Golay)", gxStarter.description(), gxStarter.source());
		var gzEigen = findOrCreateEigenfield(repo, "Gradient Z (Maxwell)", gzStarter.description(), gzStarter.source());
		var rfEigen = findOrCreateEigenfield(repo, "RF Loop", rfStarter.description(), rfStarter.source());

		double larmorHz = gamma * b0 / (2 * Math.PI);

		// Channel order matches the legacy layout [rf_I, rf_Q, gx, gz].
		return List.of(
			new FieldDefinition("B0", b0Eigen.id(), AmplitudeKind.STATIC, 0, 0, b0),
			new FieldDefinition("RF", rfEigen.id(), AmplitudeKind.QUADRATURE, larmorHz, 0, 200e-6),
			new FieldDefinition("Gradient X", gxEigen.id(), AmplitudeKind.REAL, 0, -0.030, 0.030),
			new FieldDefinition("Gradient Z", gzEigen.id(), AmplitudeKind.REAL, 0, -0.030, 0.030)
		);
	}
}
