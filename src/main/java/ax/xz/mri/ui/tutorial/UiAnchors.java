package ax.xz.mri.ui.tutorial;

import ax.xz.mri.ui.workbench.CommandId;
import javafx.scene.Node;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Process-singleton registry mapping {@link AnchorKey}s to the live JavaFX
 * {@link Node}s tutorials should point at. Existing UI code registers anchors
 * at construction time; the {@link TutorialRunner} reads back via
 * {@link #lookup} when painting overlays.
 *
 * <p>Anchors register a {@link Supplier Supplier&lt;Node&gt;} rather than a
 * bare node so resolution can be <em>lazy</em>: a menu command resolves to the
 * top-level menu button that hosts it, but those button nodes don't exist in
 * the scene graph until the {@code MenuBar}'s skin is built (after the stage
 * shows). The supplier is evaluated each lookup, so the runner picks the
 * button up as soon as it materialises. The {@code register(key, Node)}
 * overloads wrap a constant node for the common case (wizard buttons, …).
 *
 * <p>The registry tracks the latest supplier per key. Modal wizards register
 * their Next / Finish buttons on construction and overwrite the previous
 * wizard's entries — when the second wizard opens, the runner sees the new
 * buttons via the same {@link UiAnchor#WIZARD_NEXT} key. A supplier that
 * returns {@code null} (e.g. wizard closed, its button's scene gone) is
 * treated by the runner as "not mounted" and the overlay waits.
 *
 * <p>This is unapologetically static state — the studio runs a single shell
 * per process; anchors map to scene-graph identity, not multi-instance
 * routing. The {@link TutorialRunner} owns the lifecycle.
 */
public final class UiAnchors {
    private UiAnchors() {}

    private static final Map<CommandId, Supplier<Node>> COMMAND_NODES = new EnumMap<>(CommandId.class);
    private static final Map<UiAnchor, Supplier<Node>> UI_NODES = new EnumMap<>(UiAnchor.class);

    /** Register a constant node under a {@link CommandId}. Overwrites any prior entry. */
    public static synchronized void register(CommandId id, Node node) {
        register(id, node == null ? null : () -> node);
    }

    /** Register a lazily-resolved node provider under a {@link CommandId}. */
    public static synchronized void register(CommandId id, Supplier<Node> provider) {
        if (id == null) throw new IllegalArgumentException("CommandId must not be null");
        if (provider == null) COMMAND_NODES.remove(id);
        else COMMAND_NODES.put(id, provider);
    }

    /** Register a constant node under a {@link UiAnchor}. Overwrites any prior entry. */
    public static synchronized void register(UiAnchor anchor, Node node) {
        register(anchor, node == null ? null : () -> node);
    }

    /** Register a lazily-resolved node provider under a {@link UiAnchor}. */
    public static synchronized void register(UiAnchor anchor, Supplier<Node> provider) {
        if (anchor == null) throw new IllegalArgumentException("UiAnchor must not be null");
        if (provider == null) UI_NODES.remove(anchor);
        else UI_NODES.put(anchor, provider);
    }

    /** Drop the registration for {@code id} (call from owning UI's dispose / close path). */
    public static synchronized void unregister(CommandId id) {
        if (id != null) COMMAND_NODES.remove(id);
    }

    /** Drop the registration for {@code anchor} (call from owning UI's dispose / close path). */
    public static synchronized void unregister(UiAnchor anchor) {
        if (anchor != null) UI_NODES.remove(anchor);
    }

    /**
     * Resolve the live {@link Node} for an anchor key. Returns {@code null}
     * if nothing's registered or the supplier currently resolves to nothing
     * (e.g. wizard not open, menu skin not built yet) — the runner uses that
     * to decide whether to render the overlay this frame or wait.
     */
    public static synchronized Node lookup(AnchorKey key) {
        var supplier = switch (key) {
            case AnchorKey.CommandAnchor c -> COMMAND_NODES.get(c.id());
            case AnchorKey.UiNodeAnchor u  -> UI_NODES.get(u.anchor());
        };
        return supplier == null ? null : supplier.get();
    }

    /** Test-only: clear every registration. */
    static synchronized void clearForTest() {
        COMMAND_NODES.clear();
        UI_NODES.clear();
    }
}
