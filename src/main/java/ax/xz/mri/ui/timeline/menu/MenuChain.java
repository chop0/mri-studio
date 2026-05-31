package ax.xz.mri.ui.timeline.menu;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Skinnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the scene-graph from a deepest-hit Node UP to the timeline root,
 * collecting {@link TimelineContextMenuContributor} contributions and
 * merging them into a single {@link ContextMenu}.
 *
 * <p>Merge order:
 * <ol>
 *   <li>The deepest hit's {@link TimelineContextMenuContributor#menuItems()}.</li>
 *   <li>For each ancestor, a {@link SeparatorMenuItem} followed by that
 *       ancestor's {@link TimelineContextMenuContributor#menuItemsForChildren()}.</li>
 * </ol>
 *
 * <p>Both Controls and Skins are checked: Skin objects are common in this
 * codebase (e.g. {@code ClipSkin}) and may be the natural place to host
 * the menu items, so we let the {@link Skinnable#getSkin()} also implement
 * the contributor interface.
 */
public final class MenuChain {
    private MenuChain() {}

    /** Build the merged menu starting from {@code target}. Returns null if no contributions. */
    public static ContextMenu buildFor(Node target) {
        if (target == null) return null;
        var ordered = collectContributions(target);
        if (ordered.isEmpty()) return null;
        var menu = new ContextMenu();
        boolean first = true;
        for (var items : ordered) {
            if (items.isEmpty()) continue;
            if (!first) menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().addAll(items);
            first = false;
        }
        return menu.getItems().isEmpty() ? null : menu;
    }

    private static List<List<MenuItem>> collectContributions(Node target) {
        var out = new ArrayList<List<MenuItem>>();
        // The deepest hit — both as a Control AND as its Skin.
        var deepestItems = direct(target);
        if (deepestItems != null && !deepestItems.isEmpty()) out.add(deepestItems);

        // Walk upward asking each ancestor for descendant-targeted items.
        var node = target.getParent();
        while (node != null) {
            var ancestorItems = forChildren(node);
            if (ancestorItems != null && !ancestorItems.isEmpty()) out.add(ancestorItems);
            node = node.getParent();
        }
        return out;
    }

    private static List<MenuItem> direct(Node target) {
        if (target instanceof TimelineContextMenuContributor c) {
            return c.menuItems();
        }
        if (target instanceof Skinnable s && s.getSkin() instanceof TimelineContextMenuContributor c) {
            return c.menuItems();
        }
        return null;
    }

    private static List<MenuItem> forChildren(Node node) {
        if (node instanceof TimelineContextMenuContributor c) {
            return c.menuItemsForChildren();
        }
        if (node instanceof Skinnable s && s.getSkin() instanceof TimelineContextMenuContributor c) {
            return c.menuItemsForChildren();
        }
        return null;
    }
}
