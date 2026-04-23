package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Currently open workspace object. */
public final class WorkspaceSelectionViewModel {
    public final ObjectProperty<ProjectNodeId> activeNodeId = new SimpleObjectProperty<>();
}
