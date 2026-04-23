package ax.xz.mri.project;

/** Root project metadata stored in {@code mri-project.toml}. */
public record ProjectManifest(
    String name,
    String layoutFile,
    String uiStateFile
) {
    public ProjectManifest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name must not be blank");
        layoutFile = layoutFile == null || layoutFile.isBlank() ? ".mri-studio/layout.json" : layoutFile;
        uiStateFile = uiStateFile == null || uiStateFile.isBlank() ? ".mri-studio/ui-state.json" : uiStateFile;
    }
}
