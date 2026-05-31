package ax.xz.mri.ui.widget;

import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;

/**
 * Square command-style {@link MenuButton} with an icon and a tiny
 * bottom-right chevron. Replaces the default {@code MenuButton} in
 * the editor's toolbar (Outputs, color picker, route picker, …).
 *
 * <p>Same square footprint as a {@link CommandToggle}; the chevron
 * is drawn by the JavaFX skin (decorated by {@code studio-widgets.css}).
 */
public final class CommandPopupButton extends MenuButton {

    public CommandPopupButton(Node icon, String tooltip) {
        super(null, icon);
        getStyleClass().add("command-button");
        getStyleClass().add("command-popup-button");
        setFocusTraversable(false);
        if (tooltip != null) setTooltip(new Tooltip(tooltip));
    }

    public static CommandPopupButton of(StudioIcons.Kind iconKind, String tooltip) {
        return new CommandPopupButton(StudioIcons.of(iconKind), tooltip);
    }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }
}
