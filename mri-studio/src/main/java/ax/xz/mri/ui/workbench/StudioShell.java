package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** New JavaFX workbench shell with menus, command strip, dock root, and global status. */
public class StudioShell extends BorderPane {
    private final StudioSession session = new StudioSession();
    private final WorkbenchController controller = new WorkbenchController(session);
    private boolean disposed;

    public StudioShell() {
        getStyleClass().add("studio-shell");
        setTop(buildTop());
        setCenter(controller.dockRoot());
        setBottom(buildStatusBar());
    }

    public void initialize(javafx.stage.Stage stage) {
        controller.initialize(stage);
    }

    public WorkbenchController controller() {
        return controller;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        controller.saveLayoutToStore();
        controller.dispose();
        session.dispose();
    }

    private javafx.scene.Node buildTop() {
        return new javafx.scene.layout.VBox(buildMenuBar(), controller.buildMainToolStrip());
    }

    private MenuBar buildMenuBar() {
        var fileMenu = new Menu("File");
        fileMenu.getItems().addAll(
            menuItem("Open\u2026", CommandId.OPEN_FILE),
            menuItem("Reload", CommandId.RELOAD_FILE),
            new SeparatorMenuItem(),
            new MenuItem("Exit") {{
                setOnAction(event -> {
                    dispose();
                    javafx.application.Platform.exit();
                });
            }}
        );

        var viewMenu = new Menu("View");
        viewMenu.getItems().addAll(
            menuItem("Reset Layout", CommandId.RESET_LAYOUT),
            menuItem("Save Layout", CommandId.SAVE_LAYOUT),
            menuItem("Load Layout", CommandId.LOAD_LAYOUT)
        );

        var windowMenu = new Menu("Window");
        windowMenu.setOnShowing(event -> controller.populateWindowMenu(windowMenu));
        windowMenu.getItems().addAll(
            menuItem("Float Active Pane", CommandId.FLOAT_ACTIVE_PANE),
            menuItem("Dock Active Pane", CommandId.DOCK_ACTIVE_PANE),
            menuItem("Focus Active Pane", CommandId.FOCUS_ACTIVE_PANE)
        );

        var analysisMenu = new Menu("Analysis");
        analysisMenu.getItems().addAll(
            menuItem("Reset Points", CommandId.RESET_POINTS),
            menuItem("Clear User Points", CommandId.CLEAR_USER_POINTS)
        );

        return new MenuBar(fileMenu, viewMenu, windowMenu, analysisMenu);
    }

    private HBox buildStatusBar() {
        var label = new Label();
        label.textProperty().bind(controller.shellStatusProperty());
        var bar = new HBox(label);
        bar.getStyleClass().add("shell-status-bar");
        bar.setPadding(new Insets(2, 6, 2, 6));
        return bar;
    }

    private MenuItem menuItem(String label, CommandId id) {
        var item = new MenuItem(label);
        item.setOnAction(event -> controller.commandRegistry().execute(id));
        return item;
    }
}
