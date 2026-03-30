package ax.xz.mri.ui.workbench.framework;

import ax.xz.mri.ui.workbench.ContextMenuContributor;
import ax.xz.mri.ui.workbench.PaneContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Standard pane chrome header: title, tool strip, and pane menu. */
public class PaneHeader extends HBox {
    private final Label titleLabel = new Label();
    private final PaneToolStrip toolStrip = new PaneToolStrip();
    private final Button menuButton = new Button("\u2261");
    private final PaneContext context;
    private ContextMenuContributor contributor;

    public PaneHeader(PaneContext context) {
        this.context = context;
        getStyleClass().add("pane-header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 4, 2, 4));
        setSpacing(6);

        titleLabel.getStyleClass().add("pane-title");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        menuButton.getStyleClass().add("pane-menu-button");
        menuButton.setFocusTraversable(false);
        menuButton.setOnAction(event -> showMenu());

        getChildren().addAll(titleLabel, toolStrip, spacer, menuButton);
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public void setTools(Node... tools) {
        toolStrip.setTools(tools);
    }

    public void setContextMenuContributor(ContextMenuContributor contributor) {
        this.contributor = contributor;
    }

    private void showMenu() {
        var menu = new ContextMenu();
        if (context.isFloating()) {
            var dockItem = new MenuItem("Dock Pane");
            dockItem.setOnAction(event -> context.dockPane());
            menu.getItems().add(dockItem);
        } else {
            var floatItem = new MenuItem("Float Pane");
            floatItem.setOnAction(event -> context.floatPane());
            menu.getItems().add(floatItem);
        }

        var focusItem = new MenuItem("Focus Pane");
        focusItem.setOnAction(event -> context.focusPane());
        menu.getItems().add(focusItem);

        if (contributor != null) {
            menu.getItems().add(new SeparatorMenuItem());
            contributor.contribute(menu, new ax.xz.mri.ui.workbench.CommandContext(context.session(), context.paneId()));
        }

        menu.show(menuButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }
}
