package ax.xz.mri.ui.menu;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;

/**
 * Single source of truth for "the currently-open context menu". Every right-
 * click site in the studio routes its {@code show(...)} call through here
 * so the previously-open menu hides before the new one shows.
 *
 * <p>JavaFX's per-ContextMenu auto-hide ought to do this on its own, but
 * relying on it requires the popup's auto-hide event to arrive before the
 * new context-menu-requested event — and our handlers consume the press
 * which can race the popup's hide. A single explicit hand-off is reliable.
 */
public final class ActiveContextMenu {
    private static ContextMenu active;

    private ActiveContextMenu() {}

    /** Hide whatever's open and show the given menu. */
    public static void show(ContextMenu menu, Node anchor, double screenX, double screenY) {
        hide();
        if (menu == null) return;
        menu.setOnHidden(e -> { if (active == menu) active = null; });
        active = menu;
        menu.show(anchor, screenX, screenY);
    }

    /** Hide whatever's currently open. No-op if nothing is. */
    public static void hide() {
        if (active != null) {
            try { active.hide(); }
            finally { active = null; }
        }
    }
}
