package ax.xz.mri.project;

/**
 * Root project metadata stored in {@code mri-project.toml}.
 *
 * <p>{@code activeSimulation} names the simulation config the standalone
 * runner ({@code NMRStudio.runProcedure}) binds a procedure to when no
 * other simulation is specified. {@code null} means "auto-pick" — the
 * runner uses the only sim config if there's exactly one, otherwise it
 * raises an error listing the candidates.
 */
public record ProjectManifest(
    String name,
    String layoutFile,
    String uiStateFile,
    String activeSimulation
) {
    public ProjectManifest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("project name must not be blank");
        layoutFile = layoutFile == null || layoutFile.isBlank() ? ".mri-studio/layout.json" : layoutFile;
        uiStateFile = uiStateFile == null || uiStateFile.isBlank() ? ".mri-studio/ui-state.json" : uiStateFile;
        activeSimulation = activeSimulation == null || activeSimulation.isBlank() ? null : activeSimulation;
    }

    /** Convenience constructor — manifest with no active-simulation pin. */
    public ProjectManifest(String name, String layoutFile, String uiStateFile) {
        this(name, layoutFile, uiStateFile, null);
    }

    public ProjectManifest withActiveSimulation(String slug) {
        return new ProjectManifest(name, layoutFile, uiStateFile, slug);
    }
}
