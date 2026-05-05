package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaChannel;
import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-pin label assignment + RX-window pin picker. The RX gate "None" radio
 * lives at the top so the absence of a gate is a real, selectable state.
 */
final class MappingTab implements ConfigTab {

    private final EditContext ctx;
    private final VBox root = new VBox(10);

    private final ToggleGroup rxGateGroup = new ToggleGroup();
    private final RadioButton rxGateNone = new RadioButton("None (capture continuously for the whole run)");
    private final Map<RedPitayaChannel, RadioButton> rxGateRadios = new EnumMap<>(RedPitayaChannel.class);
    private final Map<RedPitayaChannel, TextField> labelFields  = new EnumMap<>(RedPitayaChannel.class);

    private boolean suppressEdits;

    MappingTab(EditContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().add("cfg-tab-inner");

        EditorRows.section(root, "GPIO labels", "Friendly names for the 16 E1 DIO pins. Labels appear "
            + "in this editor and the run metadata; tracks still bind to the raw channel ids.");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        grid.getStyleClass().add("cfg-row");
        int row = 0;
        for (var pin : RedPitayaChannel.gpioPins()) {
            var nameLabel = new Label(pin.name());
            nameLabel.getStyleClass().add("cfg-row-label");
            nameLabel.setMinWidth(80);

            var field = new TextField();
            field.setPromptText("(no label)");
            field.setPrefColumnCount(20);
            labelFields.put(pin, field);

            var radio = new RadioButton("RX gate");
            radio.setToggleGroup(rxGateGroup);
            rxGateRadios.put(pin, radio);

            grid.add(nameLabel, 0, row);
            grid.add(field, 1, row);
            var spacer = new Region();
            GridPane.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            grid.add(spacer, 2, row);
            grid.add(radio, 3, row);
            row++;
        }

        rxGateNone.setToggleGroup(rxGateGroup);
        root.getChildren().addAll(rxGateNone, grid);

        wireListeners();
        refresh(ctx.current());
    }

    @Override public Node view() { return root; }

    @Override
    public void refresh(RedPitayaConfig cfg) {
        suppressEdits = true;
        try {
            for (var entry : labelFields.entrySet()) {
                entry.getValue().setText(cfg.pinLabels().getOrDefault(entry.getKey(), ""));
            }
            if (cfg.rxGatePin() == null) {
                rxGateNone.setSelected(true);
            } else {
                var r = rxGateRadios.get(cfg.rxGatePin());
                if (r != null) r.setSelected(true);
            }
        } finally { suppressEdits = false; }
    }

    private void wireListeners() {
        for (var entry : labelFields.entrySet()) {
            var pin = entry.getKey();
            entry.getValue().textProperty().addListener((o, p, n) -> {
                if (suppressEdits) return;
                ctx.edit(c -> c.withPinLabel(pin, n));
            });
        }
        for (var entry : rxGateRadios.entrySet()) {
            var pin = entry.getKey();
            entry.getValue().selectedProperty().addListener((o, p, n) -> {
                if (suppressEdits || !n) return;
                ctx.edit(c -> c.withRxGatePin(pin));
            });
        }
        rxGateNone.selectedProperty().addListener((o, p, n) -> {
            if (suppressEdits || !n) return;
            ctx.edit(c -> c.withRxGatePin(null));
        });
    }
}
