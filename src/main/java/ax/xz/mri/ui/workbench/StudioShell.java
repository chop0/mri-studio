package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.tutorial.TutorialMenu;
import ax.xz.mri.ui.tutorial.TutorialOverlay;
import ax.xz.mri.ui.tutorial.TutorialRunner;
import ax.xz.mri.ui.tutorial.UiAnchors;
import ax.xz.mri.ui.tutorial.WelcomePane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * New JavaFX workbench shell with menus, tab bar, dock root, sidebar, and status.
 *
 * <p>The shell is a {@link StackPane} so the {@link TutorialOverlay} can sit
 * on top of the <em>entire</em> frame — menu bar included — and cut its
 * spotlight around any control, not just those in the centre work area.
 */
public class StudioShell extends StackPane {
    private final StudioSession session = new StudioSession();
    private final WorkbenchController controller = new WorkbenchController(session);
    private final BorderPane frame = new BorderPane();
    private final TutorialOverlay tutorialOverlay = new TutorialOverlay();
    private final TutorialRunner tutorialRunner =
        new TutorialRunner(tutorialOverlay, session.project.currentState());
    // Each command-bearing MenuItem → its CommandId, so we can derive the
    // command → top-level-menu mapping from the built tree and spotlight the
    // right menu button for a tutorial step.
    private final Map<MenuItem, CommandId> itemCommands = new HashMap<>();
    private boolean disposed;

    public StudioShell() {
        getStyleClass().add("studio-shell");
        frame.setTop(buildTop());
        frame.setCenter(buildCentre());
        frame.setBottom(buildStatusBar());
        getChildren().addAll(frame, tutorialOverlay);
    }

    /** The tutorial runner that drives Help ▸ Tutorials walkthroughs. */
    public TutorialRunner tutorialRunner() { return tutorialRunner; }

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
        // Main area: left sidebar + dock root + right sidebar. The welcome
        // pane stacks on top, shown only for a fresh, empty project. (The
        // tutorial overlay is a sibling of the whole frame, one level up, so
        // it can spotlight the menu bar too — see the constructor.)
        var mainArea = new HBox(controller.leftSidebar(), controller.dockRoot(), controller.rightSidebar());
        HBox.setHgrow(controller.dockRoot(), Priority.ALWAYS);

        var welcome = new WelcomePane(controller::openProjectChooser, tutorialRunner::start);
        bindWelcomeVisibility(welcome);

        return new StackPane(mainArea, welcome);
    }

    /**
     * Show the welcome pane only while the project is fresh — no documents
     * and still the default "Untitled Project" manifest. The first document
     * mutation (or an explicit Open Project) flips it off and reveals the
     * normal workbench underneath.
     */
    private void bindWelcomeVisibility(WelcomePane welcome) {
        var stateProp = session.project.currentState();
        Runnable refresh = () -> {
            var state = stateProp.get();
            boolean fresh = state != null && state.isEmpty()
                && "Untitled Project".equals(state.manifest().name());
            welcome.setVisible(fresh);
            welcome.setManaged(fresh);
        };
        stateProp.addListener((obs, o, n) -> refresh.run());
        refresh.run();
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

        // Edit menu — universal undo/redo dispatched against the focused
        // editor's scope (or the global mutation log if no editor is focused).
        var editMenu = new Menu("Edit");
        var undoItem = new MenuItem("Undo");
        undoItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Z"));
        undoItem.setOnAction(event -> controller.undoContextual());
        undoItem.disableProperty().bind(session.state.canUndoProperty().not());
        var redoItem = new MenuItem("Redo");
        redoItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+Z"));
        redoItem.setOnAction(event -> controller.redoContextual());
        redoItem.disableProperty().bind(session.state.canRedoProperty().not());
        editMenu.getItems().addAll(undoItem, redoItem);

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

        var helpMenu = TutorialMenu.buildHelpMenu(tutorialRunner::start);

        var menuBar = new MenuBar(fileMenu, editMenu, viewMenu, windowMenu, analysisMenu, helpMenu);
        registerMenuAnchors(menuBar);
        return menuBar;
    }

    private Menu buildNewMenu() {
        var newMenu = new Menu("New");
        newMenu.getItems().addAll(
            menuItem("Substance\u2026",         CommandId.NEW_SUBSTANCE, null),
            menuItem("Eigenfield\u2026",        CommandId.NEW_EIGENFIELD, null),
            new javafx.scene.control.SeparatorMenuItem(),
            menuItem("Simulation Config\u2026", CommandId.NEW_SIM_CONFIG, null),
            menuItem("Hardware Config\u2026",   CommandId.NEW_HARDWARE_CONFIG, null),
            new javafx.scene.control.SeparatorMenuItem(),
            menuItem("Sequence\u2026",          CommandId.NEW_SEQUENCE, null),
            menuItem("Procedure\u2026",         CommandId.NEW_PROCEDURE, null)
        );
        return newMenu;
    }

    private HBox buildStatusBar() {
        // Multi-segment status with vertical Separators between fields — the
        // controller publishes individual segments via shellStatusSegments,
        // and we rebuild the row whenever they change. Pinned at a fixed
        // height so segment changes never reflow the shell layout.
        var bar = new HBox(6);
        bar.getStyleClass().add("shell-status-bar");
        bar.setPadding(new Insets(2, 6, 2, 6));
        bar.setMinHeight(20);
        bar.setPrefHeight(20);
        bar.setMaxHeight(20);
        Runnable rebuild = () -> {
            bar.getChildren().clear();
            boolean first = true;
            for (var seg : controller.shellStatusSegments()) {
                if (seg == null || seg.isEmpty()) continue;
                if (!first) bar.getChildren().add(new javafx.scene.control.Separator(
                    javafx.geometry.Orientation.VERTICAL));
                bar.getChildren().add(new Label(seg));
                first = false;
            }
        };
        controller.shellStatusSegmentsProperty().addListener((obs, o, n) -> rebuild.run());
        rebuild.run();
        return bar;
    }

    private MenuItem menuItem(String label, CommandId id, KeyCombination accelerator) {
        var item = new MenuItem(label);
        item.setOnAction(event -> controller.commandRegistry().execute(id));
        if (accelerator != null) item.setAccelerator(accelerator);
        itemCommands.put(item, id);
        return item;
    }

    /**
     * Register a tutorial anchor for every command-bearing menu item, resolving
     * to the <em>top-level</em> menu button that hosts it. A {@link MenuItem}
     * isn't a scene-graph node — its skin children exist only while the menu is
     * open — so the spotlight targets the always-present top-level button (e.g.
     * "File"), and the step's bubble text spells out the rest of the path.
     * Resolution is lazy: the button nodes don't exist until the MenuBar skin
     * is built (after the stage shows), and the {@link UiAnchors} supplier is
     * evaluated per lookup.
     */
    private void registerMenuAnchors(MenuBar bar) {
        for (var topMenu : bar.getMenus()) {
            registerMenuItemsRecursively(topMenu, topMenu.getText(), bar);
        }
    }

    private void registerMenuItemsRecursively(Menu menu, String topTitle, MenuBar bar) {
        for (var item : menu.getItems()) {
            if (item instanceof Menu submenu) {
                registerMenuItemsRecursively(submenu, topTitle, bar);
            }
            var id = itemCommands.get(item);
            if (id != null) UiAnchors.register(id, () -> menuButtonByText(bar, topTitle));
        }
    }

    /**
     * The rendered top-level menu button with the given text, or {@code null}
     * if the MenuBar skin hasn't built its buttons yet (returning null lets the
     * tutorial runner wait and retry rather than spotlight a stale target).
     */
    private static Node menuButtonByText(MenuBar bar, String title) {
        if (title == null) return null;
        for (var node : bar.lookupAll(".menu-button")) {
            if (node instanceof MenuButton button && title.equals(button.getText())) return node;
        }
        return null;
    }
}
