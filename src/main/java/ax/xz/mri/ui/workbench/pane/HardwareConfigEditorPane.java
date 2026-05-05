package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.StudioIconKind;
import ax.xz.mri.ui.workbench.StudioIcons;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.time.Instant;

/**
 * Full-page tabbed editor for a {@link HardwareConfigDocument}.
 *
 * <p>Mirrors the architectural shell of {@link SimulationConfigEditorPane} —
 * title strip with a renameable name + at-a-glance metrics, a tabbed body,
 * and a footer status line. The <em>Configuration</em> tab embeds the
 * plugin's own {@link HardwareConfigEditor}, so each plugin can present
 * its own dense UI inside the same chrome.
 *
 * <h3>State management</h3>
 * <p>Every plugin-side edit dispatches a Mutation through the unified state
 * manager scoped to this hardware config; autosave + undo/redo are global.
 */
public final class HardwareConfigEditorPane extends WorkbenchPane {

    private final HardwareConfigDocument document;
    private final HardwarePlugin plugin;

    private final SimpleStringProperty nameProperty = new SimpleStringProperty();
    private final Label titleLabel = new Label();
    private final Label footerStatus = new Label();

    private final TabPane tabs = new TabPane();
    private final Tab overviewTab = new Tab("Overview");
    private final Tab configTab = new Tab("Configuration");

    private HardwareConfigEditor pluginEditor;
    private Runnable onTitleChanged;

    public HardwareConfigEditorPane(PaneContext paneContext, HardwareConfigDocument document) {
        super(paneContext);
        this.document = document;
        this.plugin = HardwarePluginRegistry.byId(document.envelope() != null
                ? document.envelope().pluginId()
                : (document.config() != null ? document.config().pluginId() : null))
            .orElse(null);
        this.nameProperty.set(document.name());
        setPaneTitle("Hardware: " + document.name());

        buildShell();
        overviewTab.setContent(scrollWrap(buildOverviewTab()));
        configTab.setContent(buildConfigTab());
    }

    public HardwareConfigDocument document() { return document; }

    public void setOnTitleChanged(Runnable listener) { this.onTitleChanged = listener; }

    public void undo() { paneContext.session().state.undoIn(
        paneContext.session().state.withinScope(Scope.indexed(Scope.root(), "hardware", document.id()))); }

    public void redo() { paneContext.session().state.redoIn(
        paneContext.session().state.withinScope(Scope.indexed(Scope.root(), "hardware", document.id()))); }

    // ── Chrome ──────────────────────────────────────────────────────────────

    private void buildShell() {
        var root = new BorderPane();
        root.getStyleClass().add("cfg-editor");
        root.setTop(buildTitleStrip());
        root.setCenter(buildTabs());
        root.setBottom(buildFooter());
        root.setFocusTraversable(true);
        root.setOnKeyPressed(this::onShortcut);
        setPaneContent(root);
    }

    private Node buildTitleStrip() {
        titleLabel.getStyleClass().add("cfg-title");
        titleLabel.textProperty().bind(nameProperty);
        titleLabel.setCursor(Cursor.TEXT);
        Tooltip.install(titleLabel, new Tooltip("Double-click to rename"));
        titleLabel.setOnMouseClicked(e -> { if (e.getClickCount() == 2) beginRename(); });

        var typeLabel = new Label("Hardware configuration");
        typeLabel.getStyleClass().add("cfg-title-meta");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var stats = new HBox(16);
        stats.setAlignment(Pos.CENTER_LEFT);
        if (plugin != null) {
            var caps = plugin.capabilities();
            stats.getChildren().addAll(
                stat("PLUGIN",  plugin.displayName()),
                stat("OUTPUTS", String.valueOf(caps.outputChannels().size())),
                stat("PROBES",  String.valueOf(caps.probeNames().size())),
                stat("MAX RATE", String.format("%.1f MS/s", caps.maxSampleRateHz() * 1e-6))
            );
        } else {
            stats.getChildren().add(stat("PLUGIN", "missing"));
        }

        var strip = new HBox(10, titleLabel, typeLabel, spacer, stats);
        strip.getStyleClass().add("cfg-title-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        return strip;
    }

    private Node stat(String label, String value) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-title-stat-label");
        var v = new Label(value);
        v.getStyleClass().add("cfg-title-stat-value");
        var box = new VBox(0, l, v);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node buildTabs() {
        tabs.getStyleClass().add("cfg-tabs");
        tabs.getTabs().addAll(overviewTab, configTab);
        for (var t : tabs.getTabs()) t.setClosable(false);
        return tabs;
    }

    private Node buildFooter() {
        footerStatus.getStyleClass().add("cfg-footer-status");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var bar = new HBox(8, footerStatus, spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("cfg-footer");
        return bar;
    }

    private void onShortcut(javafx.scene.input.KeyEvent e) {
        if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(e)) {
            redo(); e.consume();
        } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(e)) {
            undo(); e.consume();
        }
    }

    // ── Tabs ────────────────────────────────────────────────────────────────

    private Node buildOverviewTab() {
        var box = new VBox(10);
        box.getStyleClass().add("cfg-tab-inner");

        var pluginTitle = new Label("Plugin");
        pluginTitle.getStyleClass().add("cfg-section-title");
        box.getChildren().add(pluginTitle);

        if (plugin == null) {
            var warn = new Label("This config refers to a plugin that isn't loaded. "
                + "Open a project with the matching plugin available to edit.");
            warn.getStyleClass().add("cfg-row-warn");
            warn.setWrapText(true);
            box.getChildren().add(warn);
            return box;
        }

        box.getChildren().addAll(
            kv("Display name", plugin.displayName()),
            kv("Plugin id", plugin.id()),
            kv("Description", plugin.description())
        );

        var capsTitle = new Label("Capabilities");
        capsTitle.getStyleClass().add("cfg-section-title");
        box.getChildren().add(capsTitle);

        var caps = plugin.capabilities();
        box.getChildren().addAll(
            kv("Output channels", caps.outputChannels().isEmpty()
                ? "--"
                : String.join(", ", caps.outputChannels().stream().map(c -> c.sourceName() + (c.subIndex() == 0 ? "" : "[" + c.subIndex() + "]")).toList())),
            kv("Probes", caps.probeNames().isEmpty() ? "--" : String.join(", ", caps.probeNames())),
            kv("Max sample rate", String.format("%.3f MS/s", caps.maxSampleRateHz() * 1e-6)),
            kv("Min dt", String.format("%.0f ns", caps.minDtSeconds() * 1e9))
        );

        return box;
    }

    private Node buildConfigTab() {
        var initial = document.config();
        if (plugin == null || initial == null) {
            var box = new VBox(10);
            box.getStyleClass().add("cfg-tab-inner");
            var warn = new Label("Plugin missing - config cannot be edited until the plugin is loaded.");
            warn.getStyleClass().add("cfg-row-warn");
            warn.setWrapText(true);
            box.getChildren().add(warn);
            return scrollWrap(box);
        }
        pluginEditor = plugin.configEditor(initial);
        if (pluginEditor == null) {
            var box = new VBox(10);
            box.getStyleClass().add("cfg-tab-inner");
            var hint = new Label("This plugin requires no configuration.");
            hint.getStyleClass().add("cfg-empty-subtitle");
            box.getChildren().add(hint);
            return scrollWrap(box);
        }
        // Every plugin-side edit dispatches a Mutation through the unified
        // state manager — autosave + undo/redo are global and don't need
        // local Deques.
        pluginEditor.setOnEdited(() -> {
            var snap = pluginEditor.snapshot();
            var stateMgr = paneContext.session().state;
            var existing = stateMgr.current().hardwareConfig(document.id());
            if (existing == null) return;
            if (Objects.equals(existing.config(), snap)) return;
            var updated = existing.withConfig(snap);
            var scope = Scope.indexed(Scope.root(), "hardware", document.id());
            stateMgr.dispatch(new Mutation(scope, existing, updated,
                "Edit hardware config", Instant.now(), "hardware-editor",
                Mutation.Category.CONTENT));
        });
        // External state changes (undo, programmatic update) re-hydrate the editor.
        paneContext.session().state.currentProperty().addListener((obs, o, n) -> {
            if (n == null || pluginEditor == null) return;
            var doc = n.hardwareConfig(document.id());
            if (doc != null && doc.config() != null
                    && !Objects.equals(doc.config(), pluginEditor.snapshot())) {
                pluginEditor.setConfig(doc.config());
            }
        });
        return scrollWrap(pluginEditor.view());
    }

    private Node kv(String label, String value) {
        var k = new Label(label);
        k.getStyleClass().add("cfg-kv-label");
        var v = new Label(value == null ? "--" : value);
        v.getStyleClass().add("cfg-kv-value");
        v.setWrapText(true);
        var row = new HBox(8, k, v);
        row.getStyleClass().add("cfg-kv");
        return row;
    }

    private static ScrollPane scrollWrap(Node content) {
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("cfg-tab-scroll");
        return scroll;
    }

    private void beginRename() {
        var field = new TextField(nameProperty.get());
        field.setPrefColumnCount(Math.max(12, nameProperty.get().length() + 2));
        field.getStyleClass().add("cfg-title-edit");
        Runnable commit = () -> {
            var newName = field.getText() == null ? "" : field.getText().trim();
            if (!newName.isEmpty() && !newName.equals(nameProperty.get())) {
                nameProperty.set(newName);
                paneContext.session().project.renameHardwareConfig(document.id(), newName);
                setPaneTitle("Hardware: " + newName);
                if (onTitleChanged != null) onTitleChanged.run();
            }
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, was, focused) -> { if (!focused) commit.run(); });
        var titleStrip = (HBox) titleLabel.getParent();
        if (titleStrip == null) return;
        int idx = titleStrip.getChildren().indexOf(titleLabel);
        titleStrip.getChildren().set(idx, field);
        field.requestFocus();
        field.selectAll();
        // Restore the label whenever the editor loses focus.
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                int j = titleStrip.getChildren().indexOf(field);
                if (j >= 0) titleStrip.getChildren().set(j, titleLabel);
            }
        });
    }
}
