package ax.xz.mri.ui.pane;

import ax.xz.mri.model.simulation.Isochromat;
import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.framework.StudioPane;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.FontWeight;

import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * Scrollable list of isochromats with visibility toggles, delete buttons,
 * and toolbar buttons for "Defaults" / "Clear all".
 */
public class IsochromatListPane extends StudioPane {

    private final VBox listBox = new VBox();

    public IsochromatListPane(AppState s) {
        super(s);
        buildLayout();
        appState.isochromats.isochromats.addListener((ListChangeListener<Isochromat>) c -> rebuildList());
        rebuildList();
        onAttached();
    }

    @Override public String getPaneId()    { return "iso-list"; }
    @Override public String getPaneTitle() { return "Isochromats"; }

    private void buildLayout() {
        // Toolbar
        var btnDefaults = new Button("Defaults");
        var btnClear    = new Button("Clear");
        btnDefaults.setOnAction(e -> appState.isochromats.resetToDefaults());
        btnClear.setOnAction(e    -> appState.isochromats.clear());
        styleBtn(btnDefaults); styleBtn(btnClear);

        var toolbar = new HBox(4, btnDefaults, btnClear);
        toolbar.setPadding(new Insets(3, 4, 3, 4));
        toolbar.setBackground(new Background(new BackgroundFill(BG2, null, null)));

        listBox.setSpacing(0);
        listBox.setBackground(new Background(new BackgroundFill(BG, null, null)));

        var scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setBackground(new Background(new BackgroundFill(BG, null, null)));

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
        if (!iso.visible()) { gc.setFill(Color.color(0, 0, 0, 0.5)); gc.fillOval(1, 1, 8, 8); }

        // Name label
        var name = new Label(iso.name());
        name.setTextFill(iso.visible() ? TX : TX2);
        name.setFont(javafx.scene.text.Font.font("monospace", 9));
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        // Hide/show
        var btnVis = new Button(iso.visible() ? "●" : "○");
        btnVis.setTextFill(iso.visible() ? TX : TX2);
        btnVis.setFont(javafx.scene.text.Font.font("monospace", 9));
        styleBtn(btnVis);
        btnVis.setOnAction(e -> appState.isochromats.toggleVisibility(iso));

        // Delete
        var btnDel = new Button("×");
        btnDel.setTextFill(TX2);
        btnDel.setFont(javafx.scene.text.Font.font("monospace", FontWeight.BOLD, 10));
        styleBtn(btnDel);
        btnDel.setOnAction(e -> appState.isochromats.removeIsochromat(iso));

        var row = new HBox(4, dot, name, btnVis, btnDel);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 4, 2, 4));
        row.setBackground(new Background(new BackgroundFill(BG, null, null)));
        return row;
    }

    private static void styleBtn(Button b) {
        b.setStyle("""
            -fx-background-color: transparent;
            -fx-padding: 1 4 1 4;
            -fx-cursor: hand;
            -fx-background-radius: 0;
            """);
    }
}
