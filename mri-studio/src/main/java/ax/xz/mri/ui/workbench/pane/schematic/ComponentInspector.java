package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Right-hand inspector: properties of the currently-selected component. */
public final class ComponentInspector extends VBox {
    private final CircuitEditSession session;
    private final Supplier<ProjectRepository> repositorySupplier;
    private final Consumer<ProjectNodeId> onJumpToEigenfield;

    public ComponentInspector(CircuitEditSession session,
                              Supplier<ProjectRepository> repositorySupplier,
                              Consumer<ProjectNodeId> onJumpToEigenfield) {
        this.session = session;
        this.repositorySupplier = repositorySupplier;
        this.onJumpToEigenfield = onJumpToEigenfield;
        setSpacing(6);
        setPadding(new Insets(10));
        setPrefWidth(260);
        getStyleClass().add("schematic-inspector");

        session.revision.addListener((obs, oldV, newV) -> rebuild());
        session.selectedComponents.addListener((javafx.collections.SetChangeListener<ax.xz.mri.model.circuit.ComponentId>) ch -> rebuild());
        rebuild();
    }

    private void rebuild() {
        getChildren().clear();
        if (session.selectedComponents.size() != 1) {
            var hint = new Label(session.selectedComponents.isEmpty()
                ? "Click a component on the canvas to inspect it."
                : session.selectedComponents.size() + " components selected.");
            hint.setWrapText(true);
            hint.getStyleClass().add("schematic-inspector-hint");
            getChildren().add(hint);
            return;
        }

        var id = session.selectedComponents.iterator().next();
        var component = session.componentAt(id);
        if (component == null) return;

        var title = new Label(titleFor(component));
        title.getStyleClass().add("schematic-inspector-title");
        getChildren().add(title);

        var name = stringField("Name", component.name(), s -> session.replaceComponent(component.withName(s)));
        getChildren().add(name);

        switch (component) {
            case CircuitComponent.VoltageSource v -> populateVoltageSource(v);
            case CircuitComponent.SwitchComponent s -> populateSwitch(s);
            case CircuitComponent.Coil c -> populateCoil(c);
            case CircuitComponent.Probe p -> populateProbe(p);
            case CircuitComponent.Resistor r -> populateScalar("Resistance", "\u03a9", r.resistanceOhms(),
                v -> session.replaceComponent(r.withResistanceOhms(v)));
            case CircuitComponent.Capacitor c -> populateScalar("Capacitance", "F", c.capacitanceFarads(),
                v -> session.replaceComponent(c.withCapacitanceFarads(v)));
            case CircuitComponent.Inductor l -> populateScalar("Inductance", "H", l.inductanceHenry(),
                v -> session.replaceComponent(l.withInductanceHenry(v)));
            case CircuitComponent.Ground ignored -> { /* nothing else */ }
            case CircuitComponent.IdealTransformer t -> populateScalar("Turns ratio", "", t.turnsRatio(),
                v -> session.replaceComponent(t.withTurnsRatio(v)));
        }
    }

    private void populateVoltageSource(CircuitComponent.VoltageSource v) {
        var help = new Label(
            "The source's name doubles as the DAW track name \u2014 every track " +
            "with this name sums into the source each step, then its voltage drives " +
            "the coil it's wired to.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        getChildren().add(help);
        getChildren().add(enumField("Kind", AmplitudeKind.values(), v.kind(),
            k -> session.replaceComponent(v.withKind(k))));
        getChildren().add(doubleField("Carrier (Hz)", v.carrierHz(), d -> session.replaceComponent(v.withCarrierHz(d))));
        getChildren().add(doubleField("Min amplitude", v.minAmplitude(), d -> session.replaceComponent(v.withMinAmplitude(d))));
        getChildren().add(doubleField("Max amplitude", v.maxAmplitude(), d -> session.replaceComponent(v.withMaxAmplitude(d))));
        getChildren().add(doubleField("Output \u03a9", v.outputImpedanceOhms(),
            d -> session.replaceComponent(v.withOutputImpedanceOhms(d))));
    }

    private void populateSwitch(CircuitComponent.SwitchComponent s) {
        getChildren().add(doubleField("Closed \u03a9", s.closedOhms(), d -> {
            var next = new CircuitComponent.SwitchComponent(s.id(), s.name(), d, s.openOhms(), s.thresholdVolts());
            session.replaceComponent(next);
        }));
        getChildren().add(doubleField("Open \u03a9", s.openOhms(), d -> {
            var next = new CircuitComponent.SwitchComponent(s.id(), s.name(), s.closedOhms(), d, s.thresholdVolts());
            session.replaceComponent(next);
        }));
        getChildren().add(doubleField("Threshold V", s.thresholdVolts(), d -> {
            var next = new CircuitComponent.SwitchComponent(s.id(), s.name(), s.closedOhms(), s.openOhms(), d);
            session.replaceComponent(next);
        }));
    }

    private void populateCoil(CircuitComponent.Coil c) {
        // Eigenfield picker
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var label = new Label("Eigenfield");
        label.setPrefWidth(100);
        row.getChildren().add(label);

        var repo = repositorySupplier.get();
        var names = new ArrayList<String>();
        var idByName = new java.util.LinkedHashMap<String, ProjectNodeId>();
        if (repo != null) {
            for (var efId : repo.eigenfieldIds()) {
                if (repo.node(efId) instanceof EigenfieldDocument ef) {
                    names.add(ef.name());
                    idByName.put(ef.name(), ef.id());
                }
            }
        }
        var combo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(names));
        combo.setPrefWidth(130);
        String current = null;
        if (repo != null && c.eigenfieldId() != null
                && repo.node(c.eigenfieldId()) instanceof EigenfieldDocument ef) {
            current = ef.name();
        }
        combo.setValue(current);
        combo.setOnAction(e -> {
            var picked = combo.getValue();
            if (picked == null) return;
            session.replaceComponent(c.withEigenfieldId(idByName.get(picked)));
        });
        row.getChildren().add(combo);

        var jump = new Button("Open");
        jump.setOnAction(e -> {
            if (c.eigenfieldId() != null && onJumpToEigenfield != null) onJumpToEigenfield.accept(c.eigenfieldId());
        });
        jump.setDisable(c.eigenfieldId() == null);
        row.getChildren().add(jump);
        getChildren().add(row);

        getChildren().add(doubleField("Self-L (H)", c.selfInductanceHenry(),
            d -> session.replaceComponent(c.withSelfInductanceHenry(d))));
        getChildren().add(doubleField("Series \u03a9", c.seriesResistanceOhms(),
            d -> session.replaceComponent(c.withSeriesResistanceOhms(d))));
    }

    private void populateProbe(CircuitComponent.Probe p) {
        var help = new Label(
            "Emits a complex signal named after this probe, observed through the coil it's wired to.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        getChildren().add(help);
        var traceRow = new HBox(6);
        traceRow.setAlignment(Pos.CENTER_LEFT);
        var traceLabel = new Label("Signal trace");
        traceLabel.setPrefWidth(100);
        var traceName = new Label(p.name());
        traceName.getStyleClass().add("schematic-inspector-value");
        traceRow.getChildren().addAll(traceLabel, traceName);
        getChildren().add(traceRow);
        getChildren().add(doubleField("Gain", p.gain(), d -> session.replaceComponent(p.withGain(d))));
        getChildren().add(doubleField("Phase (\u00b0)", p.demodPhaseDeg(),
            d -> session.replaceComponent(p.withDemodPhaseDeg(d))));
        getChildren().add(doubleField("Load \u03a9", p.loadImpedanceOhms(),
            d -> session.replaceComponent(p.withLoadImpedanceOhms(d))));
    }

    private void populateScalar(String label, String unit, double value, Consumer<Double> onUpdate) {
        getChildren().add(doubleField(label + (unit.isEmpty() ? "" : " (" + unit + ")"), value, onUpdate));
    }

    // ───────── Field builders ─────────

    private Node stringField(String label, String value, Consumer<String> onChange) {
        var row = rowWithLabel(label);
        var field = new TextField(value);
        field.setPrefWidth(130);
        field.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) onChange.accept(field.getText());
        });
        field.setOnAction(e -> onChange.accept(field.getText()));
        row.getChildren().add(field);
        return row;
    }

    private Node doubleField(String label, double value, Consumer<Double> onChange) {
        var row = rowWithLabel(label);
        var field = new TextField(format(value));
        field.setPrefWidth(130);
        Runnable commit = () -> {
            try { onChange.accept(Double.parseDouble(field.getText().trim())); }
            catch (NumberFormatException ignored) { field.setText(format(value)); }
        };
        field.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commit.run(); });
        field.setOnAction(e -> commit.run());
        row.getChildren().add(field);
        return row;
    }

    private <T> Node enumField(String label, T[] values, T selected, Consumer<T> onChange) {
        var row = rowWithLabel(label);
        var combo = new ComboBox<T>(javafx.collections.FXCollections.observableArrayList(List.of(values)));
        combo.setValue(selected);
        combo.setPrefWidth(130);
        combo.setOnAction(e -> onChange.accept(combo.getValue()));
        row.getChildren().add(combo);
        return row;
    }

    private HBox rowWithLabel(String label) {
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var l = new Label(label);
        l.setPrefWidth(100);
        row.getChildren().add(l);
        return row;
    }

    private static String format(double value) {
        if (value == 0) return "0";
        double abs = Math.abs(value);
        if (abs >= 1e-3 && abs < 1e6) return String.format("%.6g", value);
        return String.format("%.3e", value);
    }

    private static String titleFor(CircuitComponent component) {
        return switch (component) {
            case CircuitComponent.VoltageSource v -> "Voltage source";
            case CircuitComponent.SwitchComponent s -> "Switch";
            case CircuitComponent.Coil c -> "Coil";
            case CircuitComponent.Probe p -> "Probe";
            case CircuitComponent.Ground g -> "Ground";
            case CircuitComponent.Resistor r -> "Resistor";
            case CircuitComponent.Capacitor c -> "Capacitor";
            case CircuitComponent.Inductor l -> "Inductor";
            case CircuitComponent.IdealTransformer t -> "Ideal transformer";
        };
    }
}
