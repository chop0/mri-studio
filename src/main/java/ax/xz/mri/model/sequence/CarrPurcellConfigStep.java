package ax.xz.mri.model.sequence;

import ax.xz.mri.ui.wizard.WizardStep;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Wizard step for configuring a Carr-Purcell-family echo train: how many
 * refocusing pulses to place, and the echo spacing. The 90 and 180 pulse
 * durations themselves are derived from the active config's gyromagnetic
 * ratio and RF amplitude and so are not user-editable here.
 */
final class CarrPurcellConfigStep implements WizardStep {
    static final int DEFAULT_ECHO_COUNT = 4;
    /** Default echo spacing (microseconds). 2 ms is a mild load at typical low-field rates. */
    static final double DEFAULT_ECHO_SPACING_MICROS = 2000.0;

    private final Spinner<Integer> echoCountSpinner;
    private final Spinner<Double> echoSpacingSpinner;
    private final VBox root;
    private final BooleanBinding valid;

    CarrPurcellConfigStep() {
        echoCountSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            1, 1000, DEFAULT_ECHO_COUNT));
        echoCountSpinner.setEditable(true);
        echoCountSpinner.setPrefWidth(150);
        fixCommitOnBlur(echoCountSpinner);

        echoSpacingSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
            1, 1_000_000, DEFAULT_ECHO_SPACING_MICROS, 100));
        echoSpacingSpinner.setEditable(true);
        echoSpacingSpinner.setPrefWidth(150);
        fixCommitOnBlur(echoSpacingSpinner);

        var header = new Label("Echo train");
        header.getStyleClass().add("section-header");

        var desc = new Label(
            "Controls how many refocusing pulses to place and how far apart "
            + "they sit on the timeline. Pulse durations are computed from the "
            + "config's gyromagnetic ratio and RF amplitude.");
        desc.setWrapText(true);
        desc.setStyle("-fx-text-fill: #64748b;");

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Refocusing pulses"), echoCountSpinner);
        grid.addRow(1, new Label("Echo spacing (us)"), echoSpacingSpinner);

        root = new VBox(10, header, desc, grid);
        root.setPadding(new Insets(4));

        valid = Bindings.createBooleanBinding(
            () -> echoCountSpinner.getValue() != null && echoCountSpinner.getValue() >= 1
                && echoSpacingSpinner.getValue() != null && echoSpacingSpinner.getValue() > 0,
            echoCountSpinner.valueProperty(),
            echoSpacingSpinner.valueProperty());
    }

    @Override public String title() { return "Echo train"; }
    @Override public Node content() { return root; }
    @Override public BooleanBinding validProperty() { return valid; }

    int getEchoCount() {
        Integer v = echoCountSpinner.getValue();
        return v == null ? DEFAULT_ECHO_COUNT : v;
    }

    double getEchoSpacingMicros() {
        Double v = echoSpacingSpinner.getValue();
        return v == null ? DEFAULT_ECHO_SPACING_MICROS : v;
    }

    private static void fixCommitOnBlur(Spinner<?> s) {
        s.focusedProperty().addListener((obs, was, isNow) -> {
            // Commit any in-flight text edit on blur; ignore parse failure.
            if (!isNow) try { s.increment(0); } catch (Exception ignored) {}
        });
    }
}
