package ax.xz.mri.ui.widget;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;

/**
 * Square 24-26 px toggle button, used inside a {@link CommandRibbon}.
 *
 * <p>Replaces every ControlsFX {@code ToggleSwitch} usage in the studio.
 * iOS-style sliding toggle switches read as cute mobile chrome — completely
 * wrong for a CAD app. SolidWorks uses square command buttons that fill
 * with the accent colour when on.
 *
 * <p>Optional caption appears under the button when used inside a
 * {@code CommandRibbon} group; the caption is added by the ribbon, not by
 * this class — this just renders the toggle's button surface.
 */
public final class CommandToggle extends ToggleButton {

    public CommandToggle(Node icon, String tooltip) {
        super("", icon);
        getStyleClass().add("command-button");
        setFocusTraversable(false);
        if (tooltip != null) setTooltip(new Tooltip(tooltip));
    }

    public static CommandToggle of(StudioIcons.Kind iconKind, String tooltip) {
        return new CommandToggle(StudioIcons.of(iconKind), tooltip);
    }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }
}
