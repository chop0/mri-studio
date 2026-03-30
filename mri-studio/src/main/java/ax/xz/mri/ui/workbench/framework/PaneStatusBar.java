package ax.xz.mri.ui.workbench.framework;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** Standard bottom status strip used by all workbench panes. */
public class PaneStatusBar extends HBox {
    private final Label label = new Label();

    public PaneStatusBar() {
        getStyleClass().add("pane-status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(1, 4, 1, 4));
        getChildren().add(label);
    }

    public void setText(String text) {
        label.setText(text == null ? "" : text);
    }
}
