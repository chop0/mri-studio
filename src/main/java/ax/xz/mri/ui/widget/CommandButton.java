package ax.xz.mri.ui.widget;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * Square non-toggling command button. Used inside a {@link CommandRibbon}
 * for commands that fire-and-forget (Save, Undo, Redo, Zoom buttons).
 *
 * <p>Add the {@code "primary"} CSS style class for the primary-action
 * variant (filled accent, white icon and label) — typically the Save
 * button at the right edge of the ribbon.
 */
public final class CommandButton extends Button {

    public CommandButton(Node icon, String tooltip) {
        super("", icon);
        getStyleClass().add("command-button");
        setFocusTraversable(false);
        if (tooltip != null) setTooltip(new Tooltip(tooltip));
    }

    public static CommandButton of(StudioIcons.Kind iconKind, String tooltip) {
        return new CommandButton(StudioIcons.of(iconKind), tooltip);
    }

    /** Primary-action variant — filled accent, white inverse glyph. */
    public CommandButton primary() {
        getStyleClass().add("primary");
        // Look up the icon glyph and force the inverse style class so the
        // CSS swaps the stroke colour to white.
        if (getGraphic() != null) getGraphic().getStyleClass().add("inverse");
        return this;
    }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }
}
