package ax.xz.mri.ui.workbench;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A thin horizontal bar that displays one button per minimized pane.
 * Clicking a button restores the pane to its previous BentoFX position.
 * Auto-hides when no panes are minimized.
 *
 * <p>Reusable — instantiate one per bar context (analysis, tools, etc.).
 */
public final class MinimizeBar extends HBox {
	private final Map<PaneId, Button> buttons = new EnumMap<>(PaneId.class);
	private final Consumer<PaneId> onRestore;

	public MinimizeBar(Consumer<PaneId> onRestore) {
		this.onRestore = onRestore;
		getStyleClass().add("minimize-bar");
		setAlignment(Pos.CENTER_LEFT);
		setPadding(new Insets(2, 4, 2, 4));
		setSpacing(4);
		setVisible(false);
		setManaged(false);
	}

	/** Add a button for a minimized pane. */
	public void addPane(PaneId paneId, String title) {
		if (buttons.containsKey(paneId)) return;
		var btn = new Button(title);
		btn.getStyleClass().add("minimize-bar-button");
		btn.setOnAction(e -> onRestore.accept(paneId));
		buttons.put(paneId, btn);
		getChildren().add(btn);
		setVisible(true);
		setManaged(true);
	}

	/** Remove the button for a restored pane. */
	public void removePane(PaneId paneId) {
		var btn = buttons.remove(paneId);
		if (btn != null) getChildren().remove(btn);
		if (buttons.isEmpty()) {
			setVisible(false);
			setManaged(false);
		}
	}

	/** Snapshot of currently minimized panes (for serialization). */
	public Set<PaneId> minimizedPanes() {
		return Set.copyOf(buttons.keySet());
	}

	public boolean isEmpty() {
		return buttons.isEmpty();
	}

	public boolean isMinimized(PaneId paneId) {
		return buttons.containsKey(paneId);
	}
}
