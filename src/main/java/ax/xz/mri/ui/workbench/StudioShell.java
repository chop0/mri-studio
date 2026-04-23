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
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** New JavaFX workbench shell with menus, tab bar, dock root, sidebar, and status. */
public class StudioShell extends BorderPane {
    private final StudioSession session = new StudioSession();
    private final WorkbenchController controller = new WorkbenchController(session);
    private boolean disposed;

    public StudioShell() {
        getStyleClass().add("studio-shell");
        setTop(buildTop());
        setCenter(buildCentre());
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
        return new VBox(buildMenuBar(), controller.buildMainToolStrip());
    }

    private javafx.scene.Node buildCentre() {
        // Main area: left sidebar + dock root + right sidebar
        var mainArea = new HBox(controller.leftSidebar(), controller.dockRoot(), controller.rightSidebar());
        HBox.setHgrow(controller.dockRoot(), Priority.ALWAYS);
        return mainArea;
    }

    private MenuBar buildMenuBar() {
        var fileMenu = new Menu("File");
        var saveItem = new MenuItem("Save");
        saveItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        saveItem.setOnAction(event -> controller.saveContextual());
        fileMenu.getItems().addAll(
            buildNewMenu(),
            new SeparatorMenuItem(),
            menuItem("Open Project\u2026", CommandId.OPEN_PROJECT, KeyCombination.keyCombination("Shortcut+O")),
            saveItem,
            menuItem("Save Project As\u2026", CommandId.SAVE_PROJECT_AS, KeyCombination.keyCombination("Shortcut+Shift+S")),
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
        snapItem.setSelected(true);
        session.activeEditSession.addListener((obs, o, n) -> {
            if (n != null) snapItem.selectedProperty().bindBidirectional(n.snapEnabled);
            if (o != null) snapItem.selectedProperty().unbindBidirectional(o.snapEnabled);
        });
        // Restore submenu — one item per tool window pane
        var restoreMenu = new Menu("Restore Tool Window");
        for (var paneId : PaneId.values()) {
            // Only include BentoFX-hosted tool windows
            if (paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR
                || paneId == PaneId.SEQUENCE_EDITOR || paneId == PaneId.SIM_CONFIG_EDITOR
                || paneId == PaneId.POINTS) continue;
            var item = new MenuItem(paneId.title());
            final var pid = paneId;
            item.setOnAction(event -> controller.dockPane(pid));
            restoreMenu.getItems().add(item);
        }

        viewMenu.getItems().addAll(
            snapItem,
            new SeparatorMenuItem(),
            restoreMenu,
            new SeparatorMenuItem(),
            menuItem("Reset Layout", CommandId.RESET_LAYOUT, null)
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
            menuItem("Clear User Points", CommandId.CLEAR_USER_POINTS, null)
        );

        return new MenuBar(fileMenu, viewMenu, windowMenu, analysisMenu);
    }

    private Menu buildNewMenu() {
        var newMenu = new Menu("New");
        newMenu.getItems().addAll(
            menuItem("Simulation Config\u2026", CommandId.NEW_SIM_CONFIG, null),
            menuItem("Eigenfield\u2026", CommandId.NEW_EIGENFIELD, null),
            menuItem("Sequence\u2026", CommandId.NEW_SEQUENCE, null)
        );
        return newMenu;
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
