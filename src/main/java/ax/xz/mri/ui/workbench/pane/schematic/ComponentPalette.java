package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Left-hand palette of component kinds — click a button to arm placement
 * mode. The button list is generated from
 * {@link ComponentPresenters#paletteEntries()} so adding a new kind never
 * requires touching this file.
 */
public final class ComponentPalette extends VBox {

    public ComponentPalette(Consumer<CircuitComponent> onPick) {
        setSpacing(4);
        setPadding(new Insets(10));
        setPrefWidth(200);
        getStyleClass().add("schematic-palette");

        var header = new Label("Add component");
        header.getStyleClass().add("schematic-palette-header");
        getChildren().add(header);

        String currentSection = null;
        for (var entry : ComponentPresenters.paletteEntries()) {
            if (!entry.section().equals(currentSection)) {
                addSection(entry.section());
                currentSection = entry.section();
            }
            addButton(entry.label(), entry.tooltip(), onPick, entry.factory());
        }
    }

    private void addSection(String title) {
        var header = new Label(title);
        header.getStyleClass().add("schematic-palette-section");
        header.setPadding(new Insets(8, 0, 2, 0));
        getChildren().add(header);
    }

    private void addButton(String label, String tip, Consumer<CircuitComponent> onPick,
                           java.util.function.Supplier<CircuitComponent> factory) {
        var btn = new Button(label);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.BASELINE_LEFT);
        btn.setTooltip(new Tooltip(tip));
        btn.setOnAction(e -> onPick.accept(factory.get()));
        btn.getStyleClass().add("schematic-palette-button");
        getChildren().add(btn);
    }
}
