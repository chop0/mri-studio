package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.ui.workbench.PaneId;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

import java.util.LinkedHashSet;

/** Tracks which pane is active and which panes are currently floated. */
public class DockingViewModel {
    public final ObjectProperty<PaneId> activePaneId = new SimpleObjectProperty<>();
    public final ObservableSet<PaneId> floatingPanes =
        FXCollections.observableSet(new LinkedHashSet<>());

    public void activate(PaneId paneId) {
        activePaneId.set(paneId);
    }
}
