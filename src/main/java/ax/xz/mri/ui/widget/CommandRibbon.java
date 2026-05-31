package ax.xz.mri.ui.widget;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SolidWorks Command Manager-style toolbar.
 *
 * <p>One ribbon hosts several captioned <strong>groups</strong>; each
 * group is a column of {@link CommandButton}s / {@link CommandToggle}s
 * with a small-caps caption underneath. Groups are separated by hairline
 * dividers; the rightmost group is right-aligned via an injected spacer.
 *
 * <pre>
 *   ┌── PRIMITIVES ──┬── EDIT ──┬── VIEW ──┬── RUN ──────┬───── [Save] ──┐
 *   │ ▢ ▢ ▢ ▢ ▢ ▢ ▢ │ ↶  ↷    │ −  +  ⊡  │ ⊟ Snap  ⊞ Out│               │
 *   │ Sel Sin Snc … │ undo redo│ out in fit│              │               │
 *   └────────────────┴──────────┴──────────┴──────────────┴───────────────┘
 * </pre>
 *
 * <p>Build a ribbon by adding {@link Group} instances. Each group's items
 * are laid out in a single row; the caption sits underneath. To right-align
 * a group (e.g. the Save button), call {@link #addSpacer()} before adding
 * it.
 */
public final class CommandRibbon extends HBox {

    public CommandRibbon() {
        getStyleClass().add("command-ribbon-bar");
        setAlignment(Pos.CENTER_LEFT);
    }

    /** Append a group with a caption and one row of items. */
    public CommandRibbon addGroup(String caption, Node... items) {
        return addGroup(new Group(caption, Arrays.asList(items)));
    }

    public CommandRibbon addGroup(Group group) {
        getChildren().add(group);
        return this;
    }

    /** Inject a horizontally-growing spacer so subsequent groups align right. */
    public CommandRibbon addSpacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
        return this;
    }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }

    /** Mark the last group with the {@code "last"} CSS class so its right divider hides. */
    public void finalizeLayout() {
        Group last = null;
        for (var child : getChildren()) {
            if (child instanceof Group g) last = g;
        }
        if (last != null) last.getStyleClass().add("last");
    }

    /**
     * One captioned group of related commands. Items go in a single row;
     * the caption is rendered underneath in tertiary small caps. If the
     * caption is null or empty, only the row is shown.
     */
    public static final class Group extends VBox {
        private final HBox row = new HBox();
        private final Label caption = new Label();

        public Group(String captionText, List<Node> items) {
            getStyleClass().add("command-ribbon-group");
            setAlignment(Pos.CENTER);

            row.getStyleClass().add("command-ribbon-row");
            row.setAlignment(Pos.CENTER);
            row.getChildren().addAll(items == null ? List.of() : items);

            caption.getStyleClass().add("command-ribbon-caption");
            caption.setText(captionText == null ? "" : captionText.toUpperCase());

            getChildren().add(row);
            if (captionText != null && !captionText.isEmpty()) getChildren().add(caption);
        }

        public Group(String captionText, Node... items) {
            this(captionText, items == null ? List.of() : new ArrayList<>(Arrays.asList(items)));
        }

        public HBox row() { return row; }
    }
}
