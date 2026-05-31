package ax.xz.mri.ui.timeline.scrub;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;

/**
 * Synthetic mouse-event helpers used by the interaction tests.
 *
 * <p>JavaFX exposes {@code Node.fireEvent(MouseEvent)} which dispatches an
 * event through the standard event tree, hitting the same handlers a real
 * mouse click would. By firing events at known pixel positions we can verify
 * every drag/click/scroll path without booting a real window. Tests run on
 * the JavaFX thread via {@link ax.xz.mri.support.FxTestSupport}.
 */
public final class ScrubStripFx {
    private ScrubStripFx() {}

    /** Fire a primary-button {@link MouseEvent#MOUSE_PRESSED} at local (x, y). */
    public static void press(Node target, double x, double y) {
        fireMouse(target, MouseEvent.MOUSE_PRESSED, x, y, true, MouseButton.PRIMARY, 1);
    }

    /** Fire a primary-button drag at local (x, y). */
    public static void drag(Node target, double x, double y) {
        fireMouse(target, MouseEvent.MOUSE_DRAGGED, x, y, true, MouseButton.PRIMARY, 0);
    }

    /** Fire a primary-button {@link MouseEvent#MOUSE_RELEASED} at local (x, y). */
    public static void release(Node target, double x, double y) {
        fireMouse(target, MouseEvent.MOUSE_RELEASED, x, y, false, MouseButton.PRIMARY, 1);
    }

    /** Helper: press → optional drag points → release in one call. */
    public static void clickDrag(Node target, double[]... positions) {
        if (positions.length == 0) return;
        var first = positions[0];
        press(target, first[0], first[1]);
        for (int i = 1; i < positions.length - 1; i++) {
            drag(target, positions[i][0], positions[i][1]);
        }
        var last = positions[positions.length - 1];
        if (positions.length > 1) drag(target, last[0], last[1]);
        release(target, last[0], last[1]);
    }

    /** Fire a single primary-button click (press + release) at local (x, y). */
    public static void click(Node target, double x, double y) {
        press(target, x, y);
        release(target, x, y);
    }

    /** Fire a primary-button double-click at local (x, y). */
    public static void doubleClick(Node target, double x, double y) {
        fireMouse(target, MouseEvent.MOUSE_PRESSED, x, y, true, MouseButton.PRIMARY, 2);
        fireMouse(target, MouseEvent.MOUSE_RELEASED, x, y, false, MouseButton.PRIMARY, 2);
    }

    /** Fire a vertical-scroll event with the given deltaY at local (x, y). */
    public static void scroll(Node target, double x, double y, double deltaY) {
        var screen = target.localToScreen(x, y);
        double sx = screen == null ? x : screen.getX();
        double sy = screen == null ? y : screen.getY();
        var event = new ScrollEvent(
            ScrollEvent.SCROLL,
            x, y, sx, sy,
            false, false, false, false, false, false,
            0, deltaY,
            0, deltaY,
            ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            ScrollEvent.VerticalTextScrollUnits.NONE, 0,
            0, null);
        Event.fireEvent(target, event);
    }

    private static void fireMouse(Node target, javafx.event.EventType<MouseEvent> type,
                                  double x, double y, boolean primaryDown, MouseButton button, int clickCount) {
        var screen = target.localToScreen(x, y);
        double sx = screen == null ? x : screen.getX();
        double sy = screen == null ? y : screen.getY();
        // Pick the actual node at (x, y) so target-based dispatches see the
        // right gestureSource — matters for ScrubStrip's edge handles which
        // are children of the strip.
        PickResult pick = new PickResult(target, x, y);
        var event = new MouseEvent(
            target, target, type,
            x, y, sx, sy,
            button,
            clickCount,
            false, false, false, false,
            primaryDown, false, false,
            false, false, false,
            pick);
        Event.fireEvent(target, event);
    }
}
