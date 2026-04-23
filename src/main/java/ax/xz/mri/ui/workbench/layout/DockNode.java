package ax.xz.mri.ui.workbench.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Persistent dock-tree node for the workbench. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SplitNode.class, name = "split"),
    @JsonSubTypes.Type(value = TabNode.class, name = "tab"),
    @JsonSubTypes.Type(value = PaneLeaf.class, name = "pane")
})
public sealed interface DockNode permits SplitNode, TabNode, PaneLeaf {
}
