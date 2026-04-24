package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Explorer tree state: selection plus two revision counters.
 *
 * <p>{@link #structureRevision} bumps when the project <em>shape</em>
 * changes — nodes added, removed, renamed — and drives tree rebuilds in
 * the explorer pane. {@link #contentRevision} bumps when a node's
 * <em>content</em> changes in place — a schematic edit, a sim-config
 * tweak — and is what downstream consumers (e.g.
 * {@link SequenceSimulationSession}) listen to so they invalidate caches
 * and re-simulate. The two are separate so in-place edits don't force
 * expensive tree rebuilds.
 */
public final class ExplorerTreeViewModel {
    public final ObjectProperty<ProjectNodeId> selectedNodeId = new SimpleObjectProperty<>();
    public final IntegerProperty structureRevision = new SimpleIntegerProperty();
    public final IntegerProperty contentRevision = new SimpleIntegerProperty();

    /** Bumps both counters — tree shape and content are both considered invalidated. */
    public void refresh() {
        structureRevision.set(structureRevision.get() + 1);
        contentRevision.set(contentRevision.get() + 1);
    }

    /** Bumps only the content counter. Use after in-place edits to an existing node. */
    public void markContentChanged() {
        contentRevision.set(contentRevision.get() + 1);
    }
}
