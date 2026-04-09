package ax.xz.mri.project;

import ax.xz.mri.model.simulation.EigenfieldPreset;

/**
 * Project-level eigenfield definition.
 *
 * <p>An eigenfield is the normalised 3D spatial vector field shape produced by
 * a physical field source (magnet, gradient coil, RF coil) at unit amplitude.
 * For now, the {@link EigenfieldPreset} selects an analytical generation
 * method; a future eigenfield editor will add discrete spatial data.
 *
 * <p>Eigenfields are shared across simulation configs — multiple configs can
 * reference the same eigenfield document.
 */
public record EigenfieldDocument(
	ProjectNodeId id,
	String name,
	String description,
	EigenfieldPreset preset
) implements ProjectNode {
	@Override
	public ProjectNodeKind kind() {
		return ProjectNodeKind.EIGENFIELD;
	}

	public EigenfieldDocument withName(String newName) {
		return new EigenfieldDocument(id, newName, description, preset);
	}

	public EigenfieldDocument withDescription(String newDescription) {
		return new EigenfieldDocument(id, name, newDescription, preset);
	}

	public EigenfieldDocument withPreset(EigenfieldPreset newPreset) {
		return new EigenfieldDocument(id, name, description, newPreset);
	}
}
