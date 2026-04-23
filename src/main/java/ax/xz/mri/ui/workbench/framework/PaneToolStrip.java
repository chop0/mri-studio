package ax.xz.mri.ui.workbench.framework;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;

/** Standard horizontal container for pane-local toolbar controls. */
public class PaneToolStrip extends HBox {
    public PaneToolStrip() {
        getStyleClass().add("pane-tool-strip");
        setSpacing(4);
        setAlignment(Pos.CENTER_LEFT);
    }

    public void setTools(Node... nodes) {
        getChildren().setAll(nodes);
    }
}
