package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.AmplitudeKind;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.UUID;
import java.util.function.Consumer;

/** Left-hand palette of component kinds — click a button to arm placement mode. */
public final class ComponentPalette extends VBox {

    public ComponentPalette(Consumer<CircuitComponent> onPick) {
        setSpacing(4);
        setPadding(new Insets(10));
        setPrefWidth(200);
        getStyleClass().add("schematic-palette");

        var header = new Label("Add component");
        header.getStyleClass().add("schematic-palette-header");
        getChildren().add(header);

        addSection("Sources");
        addButton("Voltage source (real)", "Real-valued drive",
            onPick, () -> newSource(AmplitudeKind.REAL, "Source"));
        addButton("RF source (I/Q)", "Two-channel RF drive",
            onPick, () -> newSource(AmplitudeKind.QUADRATURE, "RF"));
        addButton("Static source", "Fixed amplitude (B0-like)",
            onPick, () -> newSource(AmplitudeKind.STATIC, "Static"));
        addButton("Gate source", "0/1 digital signal",
            onPick, () -> newSource(AmplitudeKind.GATE, "Gate"));

        addSection("Switch");
        addButton("Switch", "Gated pass-through",
            onPick, () -> new CircuitComponent.SwitchComponent(
                newId("sw"), "Switch", 0.5, 1e9, 0.5));

        addSection("Coils + probes");
        addButton("Coil", "Bridges circuit and FOV",
            onPick, () -> new CircuitComponent.Coil(newId("coil"), "Coil", null, 0, 0));
        addButton("Probe", "Voltage measurement",
            onPick, () -> new CircuitComponent.Probe(newId("probe"), "Probe", 1, 0, Double.POSITIVE_INFINITY));

        addSection("Series passives");
        addButton("Resistor (series)", "Linear resistance inline",
            onPick, () -> new CircuitComponent.Resistor(newId("r"), "R", 50));
        addButton("Capacitor (series)", "Reactive capacitance inline",
            onPick, () -> new CircuitComponent.Capacitor(newId("c"), "C", 1e-9));
        addButton("Inductor (series)", "Reactive inductance inline",
            onPick, () -> new CircuitComponent.Inductor(newId("l"), "L", 1e-6));

        addSection("Parallel (shunt to ground)");
        addButton("Resistor (parallel)", "Shunt resistance to ground",
            onPick, () -> new CircuitComponent.ShuntResistor(newId("rshunt"), "Rp", 50));
        addButton("Capacitor (parallel)", "Shunt capacitance to ground",
            onPick, () -> new CircuitComponent.ShuntCapacitor(newId("cshunt"), "Cp", 1e-9));
        addButton("Inductor (parallel)", "Shunt inductance to ground",
            onPick, () -> new CircuitComponent.ShuntInductor(newId("lshunt"), "Lp", 1e-6));
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
        btn.setTooltip(new javafx.scene.control.Tooltip(tip));
        btn.setOnAction(e -> onPick.accept(factory.get()));
        btn.getStyleClass().add("schematic-palette-button");
        getChildren().add(btn);
    }

    private static ComponentId newId(String prefix) {
        return new ComponentId(prefix + "-" + UUID.randomUUID());
    }

    private static CircuitComponent newSource(AmplitudeKind kind, String prefix) {
        String name = prefix + " " + shortId();
        return new CircuitComponent.VoltageSource(newId("src"), name, kind, 0, 0, 1, 0);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 4);
    }
}
