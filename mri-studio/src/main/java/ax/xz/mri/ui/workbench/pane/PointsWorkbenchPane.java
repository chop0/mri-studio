package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.collections.transformation.SortedList;

import java.util.Comparator;
import java.util.List;

/** Points browser using a proper table instead of ad hoc row rebuilding. */
public class PointsWorkbenchPane extends WorkbenchPane {
    private final TableView<IsochromatEntry> table = new TableView<>();
    private final SortedList<IsochromatEntry> rows;
    private boolean syncingSelection;

    public PointsWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Points of Interest");

        var defaults = new Button("Defaults");
        defaults.setOnAction(event -> paneContext.session().points.resetToDefaults());
        var clearUser = new Button("Clear User");
        clearUser.setOnAction(event -> paneContext.session().points.clearUserPoints());
        var duplicate = new Button("Duplicate");
        duplicate.setOnAction(event -> paneContext.session().points.duplicateSelected());
        var delete = new Button("Delete");
        delete.setOnAction(event ->
            paneContext.session().points.remove(paneContext.session().selection.selectedIds));
        setToolNodes(defaults, clearUser, duplicate, delete);

        table.setEditable(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rows = new SortedList<>(
            paneContext.session().points.entries,
            Comparator.comparing((IsochromatEntry entry) -> entry.origin().name())
                .thenComparing(entry -> !entry.visible())
                .thenComparing(IsochromatEntry::name)
        );
        table.setItems(rows);
        table.getColumns().setAll(List.of(
            visibilityColumn(),
            colourColumn(),
            nameColumn(),
            rColumn(),
            zColumn(),
            originColumn()
        ));
        table.setRowFactory(view -> buildRow());
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<IsochromatEntry>) change -> syncFromTable());
        paneContext.session().selection.selectedIds.addListener((javafx.beans.InvalidationListener) obs -> syncFromSelectionModel());
        paneContext.session().points.entries.addListener((ListChangeListener<IsochromatEntry>) change -> updateStatus());

        syncFromSelectionModel();
        updateStatus();
        setPaneContent(new VBox(table));
    }

    private TableColumn<IsochromatEntry, String> colourColumn() {
        var column = new TableColumn<IsochromatEntry, String>("Colour");
        column.setSortable(false);
        column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().colour().toString()));
        column.setCellFactory(cell -> new TableCell<>() {
            private final Region swatch = new Region();

            {
                swatch.setPrefSize(10, 10);
                swatch.setMinSize(10, 10);
                swatch.setMaxSize(10, 10);
                swatch.setShape(new javafx.scene.shape.Circle(5));
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                swatch.setStyle("-fx-background-color: " + toCss(getTableRow().getItem().colour()) + ";");
                setGraphic(swatch);
            }
        });
        column.setMaxWidth(60);
        return column;
    }

    private TableColumn<IsochromatEntry, String> nameColumn() {
        var column = new TableColumn<IsochromatEntry, String>("Name");
        column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        column.setCellFactory(TextFieldTableCell.forTableColumn());
        column.setOnEditCommit(event ->
            paneContext.session().points.rename(event.getRowValue().id(), event.getNewValue()));
        return column;
    }

    private TableColumn<IsochromatEntry, String> rColumn() {
        var column = new TableColumn<IsochromatEntry, String>("r");
        column.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%.1f", cell.getValue().r())));
        column.setMaxWidth(70);
        return column;
    }

    private TableColumn<IsochromatEntry, String> zColumn() {
        var column = new TableColumn<IsochromatEntry, String>("z");
        column.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%.1f", cell.getValue().z())));
        column.setMaxWidth(70);
        return column;
    }

    private TableColumn<IsochromatEntry, String> originColumn() {
        var column = new TableColumn<IsochromatEntry, String>("Group");
        column.setCellValueFactory(cell -> new SimpleStringProperty(
            switch (cell.getValue().origin()) {
                case SCENARIO_DEFAULT -> "Scenario Defaults";
                case USER -> "User Points";
            }
        ));
        column.setMaxWidth(140);
        return column;
    }

    private TableColumn<IsochromatEntry, Boolean> visibilityColumn() {
        var column = new TableColumn<IsochromatEntry, Boolean>("");
        column.setSortable(false);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleBooleanProperty(cell.getValue().visible()));
        column.setCellFactory(cell -> new TableCell<>() {
            private final Button eyeButton = new Button("\uD83D\uDC41");

            {
                eyeButton.getStyleClass().add("visibility-eye-button");
                eyeButton.setFocusTraversable(false);
                eyeButton.setOnAction(event -> {
                    var entry = getTableRow() == null ? null : getTableRow().getItem();
                    if (entry != null) paneContext.session().points.toggleVisibility(entry.id());
                });
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                var entry = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || entry == null) {
                    setGraphic(null);
                    return;
                }
                eyeButton.setOpacity(entry.visible() ? 0.95 : 0.28);
                eyeButton.setText(entry.visible() ? "\uD83D\uDC41" : "\uD83D\uDC41");
                eyeButton.setAccessibleText(entry.visible() ? "Visible" : "Hidden");
                setGraphic(eyeButton);
            }
        });
        column.setMaxWidth(44);
        return column;
    }

    private TableRow<IsochromatEntry> buildRow() {
        var row = new TableRow<IsochromatEntry>();
        row.itemProperty().addListener((obs, oldItem, newItem) -> row.setContextMenu(buildContextMenu(newItem)));
        return row;
    }

    private ContextMenu buildContextMenu(IsochromatEntry entry) {
        var menu = new ContextMenu();
        if (entry == null) {
            var add = new MenuItem("Add Point at Centre");
            add.setOnAction(event -> paneContext.session().points.addUserPoint(0, 0, "New Point"));
            var reset = new MenuItem("Reset Defaults");
            reset.setOnAction(event -> paneContext.session().points.resetToDefaults());
            var clear = new MenuItem("Clear User Points");
            clear.setOnAction(event -> paneContext.session().points.clearUserPoints());
            menu.getItems().addAll(add, reset, clear);
            return menu;
        }

        var toggle = new MenuItem(entry.visible() ? "Hide" : "Show");
        toggle.setOnAction(event -> paneContext.session().points.toggleVisibility(entry.id()));
        var duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(event -> {
            paneContext.session().selection.setSingle(entry.id());
            paneContext.session().points.duplicateSelected();
        });
        var delete = new MenuItem("Delete");
        delete.setOnAction(event -> paneContext.session().points.remove(entry.id()));
        var lock = new MenuItem(entry.locked() ? "Unlock" : "Lock");
        lock.setOnAction(event -> paneContext.session().points.setLocked(entry.id(), !entry.locked()));
        menu.getItems().addAll(toggle, duplicate, lock, delete);
        return menu;
    }

    private void syncFromTable() {
        if (syncingSelection) return;
        syncingSelection = true;
        try {
            paneContext.session().selection.setAll(
                table.getSelectionModel().getSelectedItems().stream().map(IsochromatEntry::id).toList());
        } finally {
            syncingSelection = false;
        }
    }

    private void syncFromSelectionModel() {
        if (syncingSelection) return;
        syncingSelection = true;
        try {
            table.getSelectionModel().clearSelection();
            for (int index = 0; index < table.getItems().size(); index++) {
                if (paneContext.session().selection.isSelected(table.getItems().get(index).id())) {
                    table.getSelectionModel().select(index);
                }
            }
        } finally {
            syncingSelection = false;
        }
    }

    private void updateStatus() {
        long visible = paneContext.session().points.entries.stream().filter(IsochromatEntry::visible).count();
        setPaneStatus(String.format("%d visible / %d total", visible, paneContext.session().points.entries.size()));
    }

    private static String toCss(Color colour) {
        return String.format(
            "rgba(%d,%d,%d,%.3f)",
            (int) Math.round(colour.getRed() * 255),
            (int) Math.round(colour.getGreen() * 255),
            (int) Math.round(colour.getBlue() * 255),
            colour.getOpacity()
        );
    }
}
