package ax.xz.mri.ui.workbench;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ-style sidebar with icon+label buttons and a resizable collapsible tool panel.
 *
 * <p>Layout (LEFT sidebar):  [icon strip] [panel] [resize handle]
 * <p>Layout (RIGHT sidebar): [resize handle] [panel] [icon strip]
 *
 * The resize handle sits on the inner edge (between panel and dock root)
 * and drags to change the panel width.
 */
public final class ToolSidebar extends BorderPane {
	public record Tool(String id, String label, Node icon, Node content) {}
	public enum Side { LEFT, RIGHT }

	private final ObjectProperty<String> activeTool = new SimpleObjectProperty<>();
	private final VBox iconStrip = new VBox(1);
	private final StackPane panelArea = new StackPane();
	private final Region resizeHandle = new Region();
	private final List<Tool> tools = new ArrayList<>();
	private final ToggleGroup toggleGroup = new ToggleGroup();
	private final Side side;
	private boolean suppressToggle;
	private double panelWidth;

	public ToolSidebar(Side side, double defaultWidth) {
		this.side = side;
		this.panelWidth = defaultWidth;
		getStyleClass().add("tool-sidebar");

		// Icon strip — always visible on the outer edge
		iconStrip.getStyleClass().add("tool-sidebar-strip");
		iconStrip.setAlignment(Pos.TOP_CENTER);
		iconStrip.setPadding(new Insets(6, 0, 6, 0));

		// Panel area
		panelArea.getStyleClass().add("tool-sidebar-panel");
		panelArea.setPrefWidth(defaultWidth);
		panelArea.setMinWidth(100);

		// Resize handle — on the INNER edge (between panel and dock root)
		resizeHandle.setPrefWidth(4);
		resizeHandle.setMinWidth(4);
		resizeHandle.setMaxWidth(4);
		resizeHandle.setCursor(Cursor.H_RESIZE);
		resizeHandle.getStyleClass().add("sidebar-resize-handle");

		final double[] dragAnchor = new double[1];
		resizeHandle.setOnMousePressed(e -> dragAnchor[0] = e.getScreenX());
		resizeHandle.setOnMouseDragged(e -> {
			double dx = e.getScreenX() - dragAnchor[0];
			dragAnchor[0] = e.getScreenX();
			// LEFT sidebar: dragging right = wider; RIGHT sidebar: dragging left = wider
			double newWidth = panelWidth + (side == Side.LEFT ? dx : -dx);
			panelWidth = Math.max(100, Math.min(600, newWidth));
			panelArea.setPrefWidth(panelWidth);
		});

		// Place icon strip on outer edge
		if (side == Side.LEFT) {
			setLeft(iconStrip);
			// Resize handle on RIGHT (inner) edge
			setRight(resizeHandle);
		} else {
			setRight(iconStrip);
			// Resize handle on LEFT (inner) edge
			setLeft(resizeHandle);
		}

		// Panel and resize handle start hidden
		hidePanel();

		activeTool.addListener((obs, oldId, newId) -> {
			if (newId == null) {
				hidePanel();
				suppressToggle = true;
				if (toggleGroup.getSelectedToggle() != null) toggleGroup.getSelectedToggle().setSelected(false);
				suppressToggle = false;
			} else {
				var tool = tools.stream().filter(t -> t.id().equals(newId)).findFirst().orElse(null);
				if (tool != null) {
					var header = new Label(tool.label());
					header.getStyleClass().add("section-header");
					header.setPadding(new Insets(8, 10, 4, 10));
					var wrapper = new BorderPane();
					wrapper.setTop(header);
					wrapper.setCenter(tool.content());
					panelArea.getChildren().setAll(wrapper);
				}
				showPanel();
			}
		});
	}

	private void showPanel() {
		setCenter(panelArea);
		// Show resize handle
		resizeHandle.setVisible(true);
		resizeHandle.setManaged(true);
	}

	private void hidePanel() {
		setCenter(null);
		// Hide resize handle
		resizeHandle.setVisible(false);
		resizeHandle.setManaged(false);
	}

	public void addTool(Tool tool) {
		tools.add(tool);

		var btn = new ToggleButton();
		btn.setToggleGroup(toggleGroup);
		btn.getStyleClass().add("sidebar-tool-btn");
		btn.setTooltip(new Tooltip(tool.label()));

		// Vertical text
		var textLabel = new Label(tool.label());
		textLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #64748b;");
		textLabel.setRotate(-90);
		textLabel.setMinWidth(Region.USE_PREF_SIZE);

		var iconNode = tool.icon();
		var content = new VBox(3, iconNode, textLabel);
		content.setAlignment(Pos.CENTER);
		content.setPadding(new Insets(6, 4, 6, 4));
		btn.setGraphic(content);

		btn.setOnAction(e -> {
			if (suppressToggle) return;
			if (tool.id().equals(activeTool.get())) {
				activeTool.set(null);
				suppressToggle = true;
				btn.setSelected(false);
				suppressToggle = false;
			} else {
				activeTool.set(tool.id());
			}
		});

		iconStrip.getChildren().add(btn);
	}

	public ObjectProperty<String> activeToolProperty() { return activeTool; }

	public void updateToolContent(String id, String label, Node content) {
		for (int i = 0; i < tools.size(); i++) {
			if (tools.get(i).id().equals(id)) {
				tools.set(i, new Tool(id, label, tools.get(i).icon(), content));
				if (id.equals(activeTool.get())) {
					var header = new Label(label);
					header.getStyleClass().add("section-header");
					header.setPadding(new Insets(8, 10, 4, 10));
					var wrapper = new BorderPane();
					wrapper.setTop(header);
					wrapper.setCenter(content);
					panelArea.getChildren().setAll(wrapper);
				}
				return;
			}
		}
	}

	public void showTool(String id) {
		activeTool.set(id);
		int idx = 0;
		for (var tool : tools) {
			if (tool.id().equals(id)) {
				if (idx < iconStrip.getChildren().size()) {
					var btn = (ToggleButton) iconStrip.getChildren().get(idx);
					suppressToggle = true;
					btn.setSelected(true);
					suppressToggle = false;
				}
				break;
			}
			idx++;
		}
	}
}
