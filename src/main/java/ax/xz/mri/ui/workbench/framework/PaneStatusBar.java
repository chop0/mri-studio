package ax.xz.mri.ui.workbench.framework;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;

/**
 * Standard bottom status strip used by all workbench panes.
 *
 * <p>Two ways to write to it. {@link #setText(String)} treats the input as a
 * single inline string. {@link #setSegments(String...)} treats the inputs as
 * separate fields joined with a vertical {@link Separator} — the project
 * convention is that visible separators are UI elements, never unicode glyphs
 * baked into the text.
 */
public class PaneStatusBar extends HBox {

    public PaneStatusBar() {
        getStyleClass().add("pane-status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(1, 4, 1, 4));
        setSpacing(6);
        // The status content changes whenever the cursor moves, an isochromat is
        // selected, or a simulation finishes. Without a pinned height the
        // status bar's row height jitters by ±1 px every text update and the
        // owning BorderPane re-lays out its centre region. Pin a fixed height
        // matching the standard line metric so the row never reflows.
        setMinHeight(18);
        setPrefHeight(18);
        setMaxHeight(18);
    }

    public void setText(String text) {
        getChildren().clear();
        if (text == null || text.isEmpty()) return;
        var label = new Label(text);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(label);
    }

    /**
     * Render multiple status segments separated by a vertical
     * {@link Separator} UI element. Empty / null segments are skipped.
     */
    public void setSegments(String... segments) {
        getChildren().clear();
        if (segments == null || segments.length == 0) return;
        boolean first = true;
        for (var seg : segments) {
            if (seg == null || seg.isEmpty()) continue;
            if (!first) getChildren().add(new Separator(Orientation.VERTICAL));
            getChildren().add(new Label(seg));
            first = false;
        }
    }
}
