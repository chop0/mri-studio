package ax.xz.mri.ui.wizard;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * New Hardware Config wizard.
 *
 * <p>Steps: Name → Plugin → Plugin-specific config (if the plugin supplies a
 * {@link HardwareConfigEditor}). Mirrors the structure of
 * {@link NewSimConfigWizard}'s {@code DelegatingTemplateStep} pattern, but
 * the wrapped step comes from the chosen plugin rather than a template enum.
 *
 * <p>If no plugins are registered, the wizard short-circuits with an alert —
 * the user can't create a hardware config without a plugin to drive it.
 */
public final class NewHardwareConfigWizard {
    private NewHardwareConfigWizard() {}

    public static Optional<HardwareConfigDocument> show(Stage owner, ProjectSessionViewModel project) {
        var plugins = HardwarePluginRegistry.all();
        if (plugins.isEmpty()) {
            var alert = new Alert(Alert.AlertType.INFORMATION,
                "No hardware plugins are available. Hardware configs need a plugin to drive a real device.");
            alert.setTitle("No hardware plugins");
            alert.setHeaderText("No plugins discovered");
            alert.initOwner(owner);
            alert.showAndWait();
            return Optional.empty();
        }

        var nameStep = new NameStep("Enter a name for the hardware config", "New Hardware");

        var pluginStep = new ChoiceStep<>(
            "Plugin", "Choose the device plugin",
            plugins,
            HardwarePlugin::displayName,
            HardwarePlugin::description);

        var configStep = new PluginConfigStep(pluginStep);

        var builder = WizardDialog.<HardwareConfigDocument>builder("New Hardware Config")
            .step(nameStep)
            .step(pluginStep)
            .step(configStep)
            .resultFactory(() -> {
                var plugin = pluginStep.getValue();
                var config = configStep.snapshot(plugin);
                return project.createHardwareConfig(nameStep.getValue(), plugin, config);
            });

        return builder.build(owner).showAndWait();
    }

    /**
     * Wizard step that delegates to the selected plugin's
     * {@link HardwareConfigEditor}. Re-creates the editor each time the user
     * changes plugin so each plugin's editor sees a fresh instance of its
     * own typed config.
     */
    private static final class PluginConfigStep implements WizardStep {
        private final ChoiceStep<HardwarePlugin> pluginStep;
        private final StackPane container = new StackPane();
        private final Label placeholder;
        private final BooleanBinding valid;
        private final ObjectProperty<HardwareConfigEditor> editor = new SimpleObjectProperty<>();
        private HardwarePlugin builtFor;

        PluginConfigStep(ChoiceStep<HardwarePlugin> pluginStep) {
            this.pluginStep = pluginStep;
            placeholder = new Label("This plugin needs no further configuration.");
            placeholder.getStyleClass().add("cfg-section-subtitle");
            container.getChildren().add(placeholder);
            container.setPadding(new Insets(20));
            valid = Bindings.createBooleanBinding(() -> true);
        }

        @Override public String title() { return "Configure"; }
        @Override public Node content() { return container; }
        @Override public BooleanBinding validProperty() { return valid; }

        @Override
        public void onEnter() {
            var plugin = pluginStep.getValue();
            if (plugin == builtFor && editor.get() != null) return;
            builtFor = plugin;
            container.getChildren().clear();
            if (plugin == null) {
                container.getChildren().add(placeholder);
                editor.set(null);
                return;
            }
            var pluginEditor = plugin.configEditor(plugin.defaultConfig());
            if (pluginEditor == null) {
                container.getChildren().add(placeholder);
                editor.set(null);
                return;
            }
            editor.set(pluginEditor);
            container.getChildren().add(pluginEditor.view());
        }

        HardwareConfig snapshot(HardwarePlugin plugin) {
            if (plugin == null) return null;
            var e = editor.get();
            return e != null ? e.snapshot() : plugin.defaultConfig();
        }
    }
}
