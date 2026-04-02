package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;
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
        // Save is context-aware: in sequence editor → save sequence; otherwise → save project
        var saveItem = new MenuItem("Save");
        saveItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveItem.setOnAction(event -> controller.saveContextual());
        fileMenu.getItems().addAll(
            menuItem("Open Project\u2026", CommandId.OPEN_PROJECT, KeyCombination.keyCombination("Shortcut+O")),
            saveItem,
            menuItem("Save Project As\u2026", CommandId.SAVE_PROJECT_AS, KeyCombination.keyCombination("Shortcut+Shift+S")),
            new SeparatorMenuItem(),
            menuItem("Import JSON\u2026", CommandId.IMPORT_JSON, KeyCombination.keyCombination("Shortcut+I")),
            menuItem("Reload Import", CommandId.RELOAD_FILE, KeyCombination.keyCombination("Shortcut+R")),
            new SeparatorMenuItem(),
            menuItem("New Simulation Config\u2026", CommandId.NEW_SIM_CONFIG, null),
            new SeparatorMenuItem(),
            new MenuItem("Exit") {{
                setOnAction(event -> {
                    if (controller.confirmCloseAllEditors()) {
                        dispose();
                        javafx.application.Platform.exit();
                    }
                });
            }}
        );

        var viewMenu = new Menu("View");
        var snapItem = new javafx.scene.control.CheckMenuItem("Snap to Grid");
        snapItem.setSelected(true); // default on
        // Bind to active edit session's snap property when available
        session.activeEditSession.addListener((obs, o, n) -> {
            if (n != null) snapItem.selectedProperty().bindBidirectional(n.snapEnabled);
            if (o != null) snapItem.selectedProperty().unbindBidirectional(o.snapEnabled);
        });
        viewMenu.getItems().addAll(
            snapItem,
            new SeparatorMenuItem(),
            menuItem("Reset Layout", CommandId.RESET_LAYOUT, null),
            menuItem("Save Layout", CommandId.SAVE_LAYOUT, null),
            menuItem("Load Layout", CommandId.LOAD_LAYOUT, null)
        );

        var windowMenu = new Menu("Window");
        windowMenu.setOnShowing(event -> controller.populateWindowMenu(windowMenu));
        windowMenu.getItems().addAll(
            menuItem("Float Active Pane", CommandId.FLOAT_ACTIVE_PANE, null),
            menuItem("Dock Active Pane", CommandId.DOCK_ACTIVE_PANE, null),
            menuItem("Focus Active Pane", CommandId.FOCUS_ACTIVE_PANE, null)
        );

        var analysisMenu = new Menu("Analysis");
        analysisMenu.getItems().addAll(
            menuItem("Reset Points", CommandId.RESET_POINTS, null),
            menuItem("Clear User Points", CommandId.CLEAR_USER_POINTS, null),
            new SeparatorMenuItem(),
            menuItem("Promote to Sequence", CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE, null)
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

    private MenuItem menuItem(String label, CommandId id, KeyCombination accelerator) {
        var item = new MenuItem(label);
        item.setOnAction(event -> controller.commandRegistry().execute(id));
        if (accelerator != null) item.setAccelerator(accelerator);
        return item;
    }
}
