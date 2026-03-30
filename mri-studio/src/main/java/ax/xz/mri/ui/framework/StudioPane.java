package ax.xz.mri.ui.framework;

import ax.xz.mri.state.AppState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Abstract base for every dockable panel.
 * Provides a standard header bar (title + toolbar) and status bar.
 * Subclasses extend either this directly (for form-based panes) or {@link CanvasPane}.
 */
public abstract class StudioPane extends BorderPane {
    protected final AppState appState;
    protected final Label statusLabel = new Label();

    protected StudioPane(AppState appState) {
        this.appState = appState;
        setTop(buildHeaderBar());
        setBottom(buildStatusBarContainer());
    }

    /** Unique identifier used by the layout manager. */
    public abstract String getPaneId();

    /** Human-readable title shown in the header bar. */
    public abstract String getPaneTitle();

    /** Called when the pane is added to the scene graph. Bind listeners here. */
    protected void onAttached() {}

    /** Called when the pane is removed. Unbind listeners here. */
    protected void onDetached() {}

    /**
     * Override to add pane-specific toolbar controls to the header bar.
     * Return null or empty array for title-only headers.
     */
    protected Node[] headerControls() { return null; }

    /**
     * Update the status bar text. Call from mouse handlers or redraw triggers.
     */
    protected void setStatus(String text) {
        statusLabel.setText(text);
    }

    private HBox buildHeaderBar() {
        var title = new Label(getPaneTitle());
        title.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 11));
        title.setPadding(new Insets(0, 6, 0, 2));

        var bar = new HBox(4, title);

        var controls = headerControls();
        if (controls != null && controls.length > 0) {
            bar.getChildren().add(new Separator(javafx.geometry.Orientation.VERTICAL));
            bar.getChildren().addAll(controls);
        }

        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 4, 2, 4));
        bar.setStyle("-fx-border-color: derive(-fx-base, -20%); -fx-border-width: 0 0 1 0; -fx-background-color: derive(-fx-base, 10%);");
        return bar;
    }

    private HBox buildStatusBarContainer() {
        statusLabel.setFont(Font.font(Font.getDefault().getFamily(), 9));
        statusLabel.setStyle("-fx-text-fill: rgb(100,100,100);");
        var bar = new HBox(statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(1, 4, 1, 4));
        bar.setStyle("-fx-border-color: derive(-fx-base, -20%); -fx-border-width: 1 0 0 0; -fx-background-color: derive(-fx-base, 5%);");
        return bar;
    }
}
