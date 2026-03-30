package ax.xz.mri.ui.pane;

import ax.xz.mri.model.simulation.Isochromat;
import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.framework.StudioPane;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

/**
 * Scrollable list of isochromats (points of interest) with position display,
 * visibility toggles, delete buttons, and right-click context menus.
 * Renamed from IsochromatListPane.
 */
public class PointsOfInterestPane extends StudioPane {

    private final VBox listBox = new VBox();

    public PointsOfInterestPane(AppState s) {
        super(s);
        buildLayout();
        appState.isochromats.isochromats.addListener((ListChangeListener<Isochromat>) c -> rebuildList());
        rebuildList();
        onAttached();
    }

    @Override public String getPaneId()    { return "points-of-interest"; }
    @Override public String getPaneTitle() { return "Points of Interest"; }

    private void buildLayout() {
        // Toolbar
        var btnDefaults = new Button("Defaults");
        var btnClear    = new Button("Clear");
        btnDefaults.setOnAction(e -> appState.isochromats.resetToDefaults());
        btnClear.setOnAction(e    -> appState.isochromats.clear());

        var toolbar = new HBox(4, btnDefaults, btnClear);
        toolbar.setPadding(new Insets(3, 4, 3, 4));

        listBox.setSpacing(0);

        var scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        setTop(toolbar);
        setCenter(scroll);
    }

    private void rebuildList() {
        listBox.getChildren().clear();
        for (var iso : appState.isochromats.isochromats) {
            listBox.getChildren().add(buildRow(iso));
        }
    }

    private HBox buildRow(Isochromat iso) {
        // Colour dot
        var dot = new Canvas(10, 10);
        var gc  = dot.getGraphicsContext2D();
        gc.setFill(iso.colour());
        gc.fillOval(1, 1, 8, 8);
        if (!iso.visible()) { gc.setFill(Color.color(1, 1, 1, 0.5)); gc.fillOval(1, 1, 8, 8); }

        // Name label
        var name = new Label(iso.name());
        name.setStyle(iso.visible() ? "" : "-fx-text-fill: #999;");

        // Position label
        var pos = new Label(String.format("(r=%.1f, z=%.1f)", iso.r(), iso.z()));
        pos.setStyle("-fx-text-fill: #707070; -fx-font-size: 10px;");

        var textBox = new VBox(name, pos);
        textBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        // Hide/show button
        var btnVis = new Button(iso.visible() ? "\u25cf" : "\u25cb");
        btnVis.setOnAction(e -> appState.isochromats.toggleVisibility(iso));

        // Delete button
        var btnDel = new Button("\u00d7");
        btnDel.setOnAction(e -> appState.isochromats.removeIsochromat(iso));

        var row = new HBox(4, dot, textBox, btnVis, btnDel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 4, 2, 4));

        // Right-click context menu on row
        var ctxMenu = new ContextMenu();
        var toggleItem = new MenuItem(iso.visible() ? "Hide" : "Show");
        toggleItem.setOnAction(e -> appState.isochromats.toggleVisibility(iso));
        var deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> appState.isochromats.removeIsochromat(iso));
        ctxMenu.getItems().addAll(toggleItem, deleteItem);
        row.setOnContextMenuRequested(e -> ctxMenu.show(row, e.getScreenX(), e.getScreenY()));

        return row;
    }
}
