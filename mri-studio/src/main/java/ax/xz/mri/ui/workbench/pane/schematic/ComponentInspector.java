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
            case CircuitComponent.Multiplexer m -> populateMultiplexer(m);
            case CircuitComponent.Coil c -> populateCoil(c);
            case CircuitComponent.Probe p -> populateProbe(p);
            case CircuitComponent.Resistor r -> populateScalar("Resistance (ohms)", r.resistanceOhms(),
                v -> session.replaceComponent(r.withResistanceOhms(v)));
            case CircuitComponent.Capacitor c -> populateScalar("Capacitance (F)", c.capacitanceFarads(),
                v -> session.replaceComponent(c.withCapacitanceFarads(v)));
            case CircuitComponent.Inductor l -> populateScalar("Inductance (H)", l.inductanceHenry(),
                v -> session.replaceComponent(l.withInductanceHenry(v)));
            case CircuitComponent.ShuntResistor r -> populateScalar("Shunt R (ohms)", r.resistanceOhms(),
                v -> session.replaceComponent(r.withResistanceOhms(v)));
            case CircuitComponent.ShuntCapacitor c -> populateScalar("Shunt C (F)", c.capacitanceFarads(),
                v -> session.replaceComponent(c.withCapacitanceFarads(v)));
            case CircuitComponent.ShuntInductor l -> populateScalar("Shunt L (H)", l.inductanceHenry(),
                v -> session.replaceComponent(l.withInductanceHenry(v)));
            case CircuitComponent.IdealTransformer t -> populateScalar("Turns ratio", t.turnsRatio(),
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
        getChildren().add(doubleField("Output R (ohms)", v.outputImpedanceOhms(),
            d -> session.replaceComponent(v.withOutputImpedanceOhms(d))));
    }

    private void populateMultiplexer(CircuitComponent.Multiplexer m) {
        var help = new Label(
            "SPDT routing: common connects to 'a' when ctl is high, " +
            "to 'b' when ctl is low. Wire ctl to a source's 'active' port " +
            "for automatic T/R switching.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        getChildren().add(help);
        getChildren().add(doubleField("Closed (ohms)", m.closedOhms(), d -> session.replaceComponent(
            new CircuitComponent.Multiplexer(m.id(), m.name(), d, m.openOhms(), m.thresholdVolts()))));
        getChildren().add(doubleField("Open (ohms)", m.openOhms(), d -> session.replaceComponent(
            new CircuitComponent.Multiplexer(m.id(), m.name(), m.closedOhms(), d, m.thresholdVolts()))));
        getChildren().add(doubleField("Threshold V", m.thresholdVolts(), d -> session.replaceComponent(
            new CircuitComponent.Multiplexer(m.id(), m.name(), m.closedOhms(), m.openOhms(), d))));
    }

    private void populateSwitch(CircuitComponent.SwitchComponent s) {
        getChildren().add(doubleField("Closed (ohms)", s.closedOhms(), d -> session.replaceComponent(
            new CircuitComponent.SwitchComponent(s.id(), s.name(), d, s.openOhms(), s.thresholdVolts(), s.invertCtl()))));
        getChildren().add(doubleField("Open (ohms)", s.openOhms(), d -> session.replaceComponent(
            new CircuitComponent.SwitchComponent(s.id(), s.name(), s.closedOhms(), d, s.thresholdVolts(), s.invertCtl()))));
        getChildren().add(doubleField("Threshold V", s.thresholdVolts(), d -> session.replaceComponent(
            new CircuitComponent.SwitchComponent(s.id(), s.name(), s.closedOhms(), s.openOhms(), d, s.invertCtl()))));
        var invert = new javafx.scene.control.CheckBox("Invert ctl (close when ctl is low)");
        invert.setSelected(s.invertCtl());
        invert.setOnAction(e -> session.replaceComponent(s.withInvertCtl(invert.isSelected())));
        var row = new HBox(6, new Label(""));
        ((Label) row.getChildren().get(0)).setPrefWidth(100);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(invert);
        getChildren().add(row);
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
        getChildren().add(doubleField("Series R (ohms)", c.seriesResistanceOhms(),
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
        getChildren().add(doubleField("Carrier (Hz)", p.carrierHz(),
            d -> session.replaceComponent(p.withCarrierHz(d))));
        getChildren().add(doubleField("Phase (deg)", p.demodPhaseDeg(),
            d -> session.replaceComponent(p.withDemodPhaseDeg(d))));
        getChildren().add(doubleField("Load (ohms)", p.loadImpedanceOhms(),
            d -> session.replaceComponent(p.withLoadImpedanceOhms(d))));
    }

    private void populateScalar(String label, double value, Consumer<Double> onUpdate) {
        getChildren().add(doubleField(label, value, onUpdate));
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
            case CircuitComponent.Multiplexer m -> "Multiplexer";
            case CircuitComponent.Coil c -> "Coil";
            case CircuitComponent.Probe p -> "Probe";
            case CircuitComponent.Resistor r -> "Resistor (series)";
            case CircuitComponent.Capacitor c -> "Capacitor (series)";
            case CircuitComponent.Inductor l -> "Inductor (series)";
            case CircuitComponent.ShuntResistor r -> "Resistor (parallel)";
            case CircuitComponent.ShuntCapacitor c -> "Capacitor (parallel)";
            case CircuitComponent.ShuntInductor l -> "Inductor (parallel)";
            case CircuitComponent.IdealTransformer t -> "Ideal transformer";
        };
    }
}
