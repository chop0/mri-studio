package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Currently inspected semantic object in the right sidebar. */
public final class InspectorViewModel {
    public final ObjectProperty<ProjectNodeId> inspectedNodeId = new SimpleObjectProperty<>();
}
