package ax.xz.mri.ui.widget;

import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

/**
 * Tiny coloured pill showing a probe's run context — sim or hw.
 *
 * <p>Replaces the leading "S " / "H " prefix on probe-row labels. Two
 * variants chosen by CSS class:
 * <ul>
 *   <li>{@code .context-sim} — accent blue background, "SIM" text.</li>
 *   <li>{@code .context-hw}  — desaturated amber background, "HW" text.</li>
 * </ul>
 */
public final class ContextBadge extends Label {

    public enum Context { SIM, HW }

    public ContextBadge(Context context) {
        super(context == Context.SIM ? "SIM" : "HW");
        getStyleClass().addAll("context-badge", "context-" + context.name().toLowerCase());
        setTooltip(new Tooltip(context == Context.SIM ? "Simulator probe" : "Hardware probe"));
    }

    public static ContextBadge sim() { return new ContextBadge(Context.SIM); }
    public static ContextBadge hw()  { return new ContextBadge(Context.HW);  }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/widget/studio-widgets.css").toExternalForm();
    }
}
