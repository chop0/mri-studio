package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.workbench.layout.DockNode;
import ax.xz.mri.ui.workbench.layout.PaneLeaf;
import ax.xz.mri.ui.workbench.layout.SplitNode;
import ax.xz.mri.ui.workbench.layout.TabNode;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.DockContainer;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure conversion between persisted {@link DockNode} trees and live BentoFX
 * containers — capture and restore in one place, no controller state.
 */
final class LayoutTreeIO {
    private LayoutTreeIO() {}

    /** Walk a live BentoFX container into a persisted {@link DockNode}. */
    static DockNode capture(DockContainer container, Function<Dockable, PaneId> paneIdOf) {
        if (container instanceof DockContainerBranch branch) {
            var children = branch.getChildContainers();
            if (children.isEmpty()) return null;
            if (children.size() == 1) return capture(children.getFirst(), paneIdOf);
            return captureSplit(branch.getOrientation(), children, branch.getDividerPositions(), 0, paneIdOf);
        }
        if (container instanceof DockContainerLeaf leaf) {
            var tabs = new ArrayList<DockNode>();
            int selectedIdx = 0;
            for (var dockable : leaf.getDockables()) {
                var paneId = paneIdOf.apply(dockable);
                if (paneId != null) {
                    tabs.add(new PaneLeaf(paneId));
                    if (leaf.getSelectedDockable() == dockable) selectedIdx = tabs.size() - 1;
                }
            }
            if (tabs.isEmpty()) return null;
            if (tabs.size() == 1) return tabs.getFirst();
            return new TabNode(tabs, selectedIdx);
        }
        return null;
    }

    /** Recursively build binary {@link SplitNode}s from an N-child branch. */
    private static DockNode captureSplit(
        Orientation orientation,
        List<? extends DockContainer> children,
        double[] dividers,
        int fromIndex,
        Function<Dockable, PaneId> paneIdOf
    ) {
        if (fromIndex >= children.size() - 1) return capture(children.get(fromIndex), paneIdOf);
        var first = capture(children.get(fromIndex), paneIdOf);
        var rest = (fromIndex < children.size() - 2)
            ? captureSplit(orientation, children, dividers, fromIndex + 1, paneIdOf)
            : capture(children.get(fromIndex + 1), paneIdOf);
        double divPos = fromIndex < dividers.length ? dividers[fromIndex] : 0.5;
        return new SplitNode(orientation, divPos, first, rest);
    }

    /** Build a live BentoFX container tree from a persisted {@link DockNode}. */
    static DockContainer restore(
        DockBuilding builder,
        DockNode node,
        BiFunction<DockBuilding, PaneId, Dockable> dockableFactory,
        BiConsumer<PaneId, DockContainerLeaf> registerHome
    ) {
        if (node instanceof PaneLeaf paneLeaf) {
            return restoreLeaf(builder, paneLeaf.paneId(), dockableFactory, registerHome);
        }
        if (node instanceof TabNode tabNode) {
            var leaf = builder.leaf("tab-" + System.nanoTime());
            leaf.setPruneWhenEmpty(false);
            for (var tab : tabNode.tabs()) {
                if (tab instanceof PaneLeaf pl) {
                    var dockable = dockableFactory.apply(builder, pl.paneId());
                    if (dockable != null) {
                        registerHome.accept(pl.paneId(), leaf);
                        leaf.addDockable(dockable);
                    }
                }
            }
            if (tabNode.selectedIndex() >= 0 && tabNode.selectedIndex() < leaf.getDockables().size()) {
                leaf.selectDockable(leaf.getDockables().get(tabNode.selectedIndex()));
            }
            return leaf;
        }
        if (node instanceof SplitNode splitNode) {
            var branch = builder.branch("split-" + System.nanoTime());
            branch.setOrientation(splitNode.orientation());
            var first = restore(builder, splitNode.first(), dockableFactory, registerHome);
            var second = restore(builder, splitNode.second(), dockableFactory, registerHome);
            if (first != null) branch.addContainer(first);
            if (second != null) branch.addContainer(second);
            if (first != null && second != null) {
                deferDividers(branch, splitNode.dividerPosition());
            }
            return branch;
        }
        return null;
    }

    private static DockContainerLeaf restoreLeaf(
        DockBuilding builder, PaneId paneId,
        BiFunction<DockBuilding, PaneId, Dockable> dockableFactory,
        BiConsumer<PaneId, DockContainerLeaf> registerHome
    ) {
        var leaf = builder.leaf(paneId.name().toLowerCase());
        leaf.setPruneWhenEmpty(true);
        var dockable = dockableFactory.apply(builder, paneId);
        if (dockable != null) {
            registerHome.accept(paneId, leaf);
            leaf.addDockable(dockable);
            leaf.selectDockable(dockable);
        }
        return leaf;
    }

    /** Set divider positions, deferring to after the scene is attached if needed. */
    static void deferDividers(DockContainerBranch branch, double... positions) {
        branch.setDividerPositions(positions);
        Platform.runLater(() -> branch.setDividerPositions(positions));
    }
}
