package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Explorer tree state: selection and a revision counter that signals tree rebuilds. */
public final class ExplorerTreeViewModel {
    public final ObjectProperty<ProjectNodeId> selectedNodeId = new SimpleObjectProperty<>();
    public final IntegerProperty structureRevision = new SimpleIntegerProperty();

    public void refresh() {
        structureRevision.set(structureRevision.get() + 1);
    }
}
