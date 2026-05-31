package ax.xz.mri.ui.tutorial;

import ax.xz.mri.ui.tutorial.tutorials.BlochCpmgTutorial;
import ax.xz.mri.ui.tutorial.tutorials.NvCoherentTutorial;

import java.util.List;
import java.util.Optional;

/**
 * Catalogue of built-in tutorials. The Help ▸ Tutorials submenu and the
 * welcome pane both read from {@link #all()}; tests reference the named
 * constants directly.
 */
public final class TutorialLibrary {
    private TutorialLibrary() {}

    public static final Tutorial NV_COHERENT = NvCoherentTutorial.build();
    public static final Tutorial BLOCH_CPMG  = BlochCpmgTutorial.build();

    private static final List<Tutorial> ALL = List.of(NV_COHERENT, BLOCH_CPMG);

    public static List<Tutorial> all() { return ALL; }

    public static Optional<Tutorial> byId(String id) {
        if (id == null) return Optional.empty();
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
