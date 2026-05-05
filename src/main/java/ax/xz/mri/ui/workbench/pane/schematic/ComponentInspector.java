package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.InspectorEnv;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.InspectorFields;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Right-hand inspector: properties of the currently-selected component.
 * Every per-kind concern lives on the kind's
 * {@link ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenter};
 * this class owns only the top-of-panel chrome (title, name field) and
 * the "nothing / multiple selected" messaging.
 */
public final class ComponentInspector extends VBox {
    private final CircuitEditSession session;
    private final InspectorEnv env;

    public ComponentInspector(CircuitEditSession session,
                              Supplier<ProjectState> repositorySupplier,
                              Consumer<ProjectNodeId> onJumpToEigenfield) {
        this.session = session;
        this.env = new InspectorEnv(session, repositorySupplier, onJumpToEigenfield);
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

        var presenter = ComponentPresenters.of(component);

        var title = new Label(presenter.displayName());
        title.getStyleClass().add("schematic-inspector-title");
        getChildren().add(title);

        Node nameField = InspectorFields.stringField("Name", component.name(),
            s -> session.replaceComponent(component.withName(s)));
        getChildren().add(nameField);

        presenter.buildInspector(this, env);
    }
}
