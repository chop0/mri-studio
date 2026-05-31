package ax.xz.mri.ui.widget;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Compact chip showing a track's routing for one context (sim / hw).
 *
 * <p>Visible at a glance: a coloured dot + small-caps context label +
 * the route name (or "—" when unrouted). Click opens a popup menu
 * (caller wires it via {@link #menu()}).
 *
 * <pre>
 *   ●sim Source 3151        ●hw Coil A          ○hw —
 *   [routed]                 [routed]            [unrouted]
 * </pre>
 *
 * <p>Pseudo-classes drive visual states:
 * <ul>
 *   <li>{@code :routed} — the chip has a target route; dot is solid accent.</li>
 *   <li>{@code :unrouted} — no target; dot is muted, name is "—".</li>
 *   <li>{@code .context-sim} / {@code .context-hw} — chooses dot colour.</li>
 * </ul>
 */
public final class RouteChip extends MenuButton {

    public enum Context { SIM, HW }

    private static final PseudoClass ROUTED   = PseudoClass.getPseudoClass("routed");
    private static final PseudoClass UNROUTED = PseudoClass.getPseudoClass("unrouted");

    private final Context context;
    private final Region dot = new Region();
    private final Label  contextLabel = new Label();
    private final Label  nameLabel = new Label("—");
    private final BooleanProperty routed = new SimpleBooleanProperty(false);
    private final StringProperty  name   = new SimpleStringProperty("");

    public RouteChip(Context context) {
        super();
        this.context = context;
        getStyleClass().addAll("route-chip", "context-" + context.name().toLowerCase());

        dot.getStyleClass().add("route-chip-dot");

        contextLabel.setText(context.name());
        contextLabel.getStyleClass().add("route-chip-context");

        nameLabel.getStyleClass().add("route-chip-name");

        var content = new HBox(4, dot, contextLabel, nameLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        // We use the MenuButton's graphic slot to host the chip layout —
        // the popup arrow is hidden via CSS for a cleaner chip look.
        setGraphic(content);
        // Also stretch
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        routed.addListener((obs, was, now) -> applyRoutedState(now));
        name.addListener((obs, was, now) -> {
            nameLabel.setText(now == null || now.isEmpty() ? "—" : now);
        });
        applyRoutedState(false);
    }

    private void applyRoutedState(boolean isRouted) {
        pseudoClassStateChanged(ROUTED,   isRouted);
        pseudoClassStateChanged(UNROUTED, !isRouted);
        if (!isRouted) nameLabel.setText("—");
    }

    public Context context() { return context; }
    public BooleanProperty routedProperty() { return routed; }
    public StringProperty  nameProperty()   { return name;   }

    public RouteChip setRouteName(String routeName) {
        name.set(routeName == null ? "" : routeName);
        routed.set(routeName != null && !routeName.isEmpty());
        return this;
    }

    public RouteChip setUnrouted() {
        name.set("");
        routed.set(false);
        return this;
    }

    public RouteChip withTooltip(String tooltipText) {
        if (tooltipText != null && !tooltipText.isEmpty()) {
            setTooltip(new Tooltip(tooltipText));
        }
        return this;
    }

    /** Direct access to the underlying menu so callers can populate items. */
    public MenuButton menu() { return this; }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }
}
