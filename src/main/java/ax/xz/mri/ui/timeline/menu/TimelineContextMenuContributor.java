package ax.xz.mri.ui.timeline.menu;

import javafx.scene.control.MenuItem;

import java.util.List;

/**
 * Contract for any timeline scene-graph node that wants to contribute
 * items to a heritable context menu.
 *
 * <p>The studio's timeline used to have rich right-click menus on every
 * element (clip, track header, lane background, time-axis strip). The
 * scene-graph rebuild lost them. The new approach is heritable — a
 * single {@link MenuChain} walks UP from the press target through its
 * ancestor chain, asking each {@code TimelineContextMenuContributor} for
 * items, and merges the results in deepest-first order.
 *
 * <p>Two contribution points so a parent can both:
 * <ul>
 *   <li>{@link #menuItems()} — items shown when this node is the deepest
 *       hit (e.g. a clip's "Cut / Copy / Paste / Delete" entries);</li>
 *   <li>{@link #menuItemsForChildren()} — items shown for any descendant
 *       hit, separated by a divider (e.g. a TrackLane's "Add clip ▸"
 *       submenu still appears when right-clicking on a clip in that
 *       lane).</li>
 * </ul>
 */
public interface TimelineContextMenuContributor {
    /** Items to show when this node is the directly-hit element. */
    default List<MenuItem> menuItems() { return List.of(); }

    /** Items to show below a divider when any descendant is hit. */
    default List<MenuItem> menuItemsForChildren() { return List.of(); }
}
