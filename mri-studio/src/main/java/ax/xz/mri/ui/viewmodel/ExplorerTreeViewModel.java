package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Explorer tree state over the semantic project repository. */
public final class ExplorerTreeViewModel {
    public final ObjectProperty<ProjectRepository> repository = new SimpleObjectProperty<>(ProjectRepository.untitled());
    public final ObjectProperty<ProjectNodeId> selectedNodeId = new SimpleObjectProperty<>();
    public final IntegerProperty structureRevision = new SimpleIntegerProperty();

    public void refresh() {
        structureRevision.set(structureRevision.get() + 1);
    }
}
