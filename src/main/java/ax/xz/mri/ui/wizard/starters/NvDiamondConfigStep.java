package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.model.simulation.NvSimulationMethod;
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
 * Wizard step for the NV-diamond template's <em>simulation method</em> — how
 * finely NV–NV dipolar coupling is emulated.
 *
 * <p>NV centres closer than the coupling cutoff are evolved jointly as a
 * {@code 2^k} density matrix (genuine quantum entanglement / flip-flop); the
 * max-cluster-size caps {@code k} so the cost stays linear in the number of
 * NVs rather than exponential — "emulate some of the interactions, not all".
 * Max cluster size 1 is the fully-independent classical model.
 */
final class NvDiamondConfigStep implements WizardStep {

    /** Default joint-cluster cap surfaced by the wizard. */
    static final int DEFAULT_MAX_CLUSTER_SIZE = 3;
    /** Default coupling cutoff (nm): pairs closer than this couple. */
    static final double DEFAULT_CUTOFF_NM = 30.0;

    private final Spinner<Integer> maxClusterSpinner;
    private final Spinner<Double> cutoffNmSpinner;
    private final BooleanBinding valid = Bindings.createBooleanBinding(() -> true);
    private final VBox root;

    NvDiamondConfigStep() {
        maxClusterSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            1, 6, DEFAULT_MAX_CLUSTER_SIZE, 1));
        maxClusterSpinner.setEditable(true);
        maxClusterSpinner.setPrefWidth(150);
        commitOnBlur(maxClusterSpinner);

        cutoffNmSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
            0, 500, DEFAULT_CUTOFF_NM, 5));
        cutoffNmSpinner.setEditable(true);
        cutoffNmSpinner.setPrefWidth(150);
        commitOnBlur(cutoffNmSpinner);

        var header = new Label("NV interaction model");
        header.getStyleClass().add("section-header");

        var desc = new Label("Centres within the coupling cutoff are simulated jointly as a "
            + "quantum cluster carrying their dipolar coupling; the cap bounds how many NVs "
            + "couple at once so the cost stays manageable. Set the cap to 1 for the classical "
            + "independent-NV model.");
        desc.setWrapText(true);
        desc.setStyle("-fx-text-fill: #64748b;");

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Max cluster size"), maxClusterSpinner);
        grid.addRow(1, new Label("Coupling cutoff (nm)"), cutoffNmSpinner);

        root = new VBox(10, header, desc, grid);
        root.setPadding(new Insets(4));
    }

    @Override public String title() { return "NV Method"; }
    @Override public Node content() { return root; }
    @Override public BooleanBinding validProperty() { return valid; }

    /** Build the configured simulation method from the current spinner values. */
    NvSimulationMethod simulationMethod() {
        int cap = maxClusterSpinner.getValue() == null ? DEFAULT_MAX_CLUSTER_SIZE : maxClusterSpinner.getValue();
        double cutoffNm = cutoffNmSpinner.getValue() == null ? DEFAULT_CUTOFF_NM : cutoffNmSpinner.getValue();
        return new NvSimulationMethod.ClusteredQubitHamiltonian(cap, cutoffNm * 1e-9);
    }

    private static void commitOnBlur(Spinner<?> s) {
        s.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) try { s.increment(0); } catch (RuntimeException ignored) {}
        });
    }
}
