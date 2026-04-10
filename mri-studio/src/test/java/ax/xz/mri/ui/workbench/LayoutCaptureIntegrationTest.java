package ax.xz.mri.ui.workbench;

import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.workbench.layout.DockNode;
import ax.xz.mri.ui.workbench.layout.PaneLeaf;
import ax.xz.mri.ui.workbench.layout.SplitNode;
import ax.xz.mri.ui.workbench.layout.TabNode;
import ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that create real BentoFX trees, capture their structure,
 * serialize to JSON, deserialize, and verify the round-trip matches.
 * Runs on the JavaFX Application Thread.
 */
class LayoutCaptureIntegrationTest {

    // --- Helper: build a mini BentoFX tree and capture it ---

    record CapturedTree(
        DockNode captured,
        Map<PaneId, Dockable> dockables,
        Bento bento
    ) {}

    /**
     * Build a BentoFX tree matching the app's default layout:
     *   branch(VERTICAL):
     *     leaf(analysis tabs: CROSS_SECTION, SPHERE, PHASE_MAP_Z, ...)
     *     leaf(TIMELINE)
     */
    static CapturedTree buildAndCapture() {
        var bento = new Bento();
        var builder = bento.dockBuilding();
        var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

        // Analysis leaf with multiple tabs
        var analysisLeaf = builder.leaf("analysis");
        analysisLeaf.setPruneWhenEmpty(false);
        var analysisPaneIds = List.of(
            PaneId.CROSS_SECTION, PaneId.SPHERE,
            PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
            PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE);
        for (var paneId : analysisPaneIds) {
            var d = builder.dockable(paneId.name());
            d.setTitle(paneId.title());
            d.setNode(new Label(paneId.name()));
            dockables.put(paneId, d);
            analysisLeaf.addDockable(d);
        }
        analysisLeaf.selectDockable(analysisLeaf.getDockables().getFirst());

        // Timeline leaf
        var timelineLeaf = builder.leaf("timeline");
        var timelineDockable = builder.dockable(PaneId.TIMELINE.name());
        timelineDockable.setTitle(PaneId.TIMELINE.title());
        timelineDockable.setNode(new Label("Timeline"));
        dockables.put(PaneId.TIMELINE, timelineDockable);
        timelineLeaf.addDockable(timelineDockable);

        // Branch
        var branch = builder.branch("bottom");
        branch.setOrientation(Orientation.VERTICAL);
        branch.addContainers(analysisLeaf, timelineLeaf);

        // Capture using the same logic as WorkbenchController.captureNode
        var captured = captureNode(branch, dockables);

        return new CapturedTree(captured, dockables, bento);
    }

    // --- Copy of captureNode logic (pure, no WorkbenchController dependency) ---

    static DockNode captureNode(software.coley.bentofx.layout.DockContainer container,
                                Map<PaneId, Dockable> dockableMap) {
        if (container instanceof DockContainerBranch branch) {
            var children = branch.getChildContainers();
            if (children.isEmpty()) return null;
            if (children.size() == 1) return captureNode(children.getFirst(), dockableMap);
            var dividers = branch.getDividerPositions();
            return captureSplit(branch.getOrientation(), children, dividers, 0, dockableMap);
        } else if (container instanceof DockContainerLeaf leaf) {
            var tabs = new ArrayList<DockNode>();
            int selectedIdx = 0;
            for (int i = 0; i < leaf.getDockables().size(); i++) {
                var d = leaf.getDockables().get(i);
                var paneId = paneIdOf(d, dockableMap);
                if (paneId != null) {
                    tabs.add(new PaneLeaf(paneId));
                    if (leaf.getSelectedDockable() == d) selectedIdx = tabs.size() - 1;
                }
            }
            if (tabs.isEmpty()) return null;
            if (tabs.size() == 1) return tabs.getFirst();
            return new TabNode(tabs, selectedIdx);
        }
        return null;
    }

    static DockNode captureSplit(
            Orientation orientation,
            List<? extends software.coley.bentofx.layout.DockContainer> children,
            double[] dividers, int fromIndex,
            Map<PaneId, Dockable> dockableMap) {
        if (fromIndex >= children.size() - 1) return captureNode(children.get(fromIndex), dockableMap);
        var first = captureNode(children.get(fromIndex), dockableMap);
        var rest = (fromIndex < children.size() - 2)
            ? captureSplit(orientation, children, dividers, fromIndex + 1, dockableMap)
            : captureNode(children.get(fromIndex + 1), dockableMap);
        double divPos = fromIndex < dividers.length ? dividers[fromIndex] : 0.5;
        return new SplitNode(orientation, divPos, first, rest);
    }

    static PaneId paneIdOf(Dockable dockable, Map<PaneId, Dockable> dockableMap) {
        for (var entry : dockableMap.entrySet()) {
            if (entry.getValue() == dockable) return entry.getKey();
        }
        return null;
    }

    // --- Tests ---

    @Test
    void captureDefaultLayoutProducesCorrectStructure() {
        FxTestSupport.runOnFxThread(() -> {
            var result = buildAndCapture();
            assertNotNull(result.captured(), "Capture should produce a non-null tree");

            // Should be a SplitNode(VERTICAL, ...)
            assertInstanceOf(SplitNode.class, result.captured());
            var split = (SplitNode) result.captured();
            assertEquals(Orientation.VERTICAL, split.orientation());

            // First child: TabNode with 7 analysis panes
            assertInstanceOf(TabNode.class, split.first());
            var tabs = (TabNode) split.first();
            assertEquals(7, tabs.tabs().size(), "Should have 7 analysis pane tabs");
            assertEquals(0, tabs.selectedIndex());

            // Verify all expected pane IDs are present
            var expectedIds = List.of(
                PaneId.CROSS_SECTION, PaneId.SPHERE,
                PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
                PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE);
            for (int i = 0; i < expectedIds.size(); i++) {
                assertInstanceOf(PaneLeaf.class, tabs.tabs().get(i));
                assertEquals(expectedIds.get(i), ((PaneLeaf) tabs.tabs().get(i)).paneId());
            }

            // Second child: PaneLeaf(TIMELINE)
            assertInstanceOf(PaneLeaf.class, split.second());
            assertEquals(PaneId.TIMELINE, ((PaneLeaf) split.second()).paneId());
        });
    }

    @Test
    void captureAndSerializeRoundTrips(@TempDir Path tempDir) {
        FxTestSupport.runOnFxThread(() -> {
            var result = buildAndCapture();
            var state = new WorkbenchLayoutState(result.captured(), List.of());

            // Serialize and deserialize
            var store = new PersistentLayoutStore(tempDir.resolve("layout.json"));
            store.save(state);
            var loaded = store.load();

            assertTrue(loaded.isPresent());
            assertEquals(state, loaded.get(), "Round-tripped state should match original");
        });
    }

    @Test
    void captureAfterDragProducesUpdatedStructure() {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            // Start with 2 panes in one leaf
            var leaf = builder.leaf("shared");
            leaf.setPruneWhenEmpty(false);
            var d1 = builder.dockable(PaneId.CROSS_SECTION.name());
            d1.setTitle("Geo"); d1.setNode(new Label("Geo"));
            var d2 = builder.dockable(PaneId.SPHERE.name());
            d2.setTitle("Sphere"); d2.setNode(new Label("Sphere"));
            dockables.put(PaneId.CROSS_SECTION, d1);
            dockables.put(PaneId.SPHERE, d2);
            leaf.addDockable(d1);
            leaf.addDockable(d2);

            // Capture — should be TabNode with 2 tabs
            var captured = captureNode(leaf, dockables);
            assertInstanceOf(TabNode.class, captured);
            assertEquals(2, ((TabNode) captured).tabs().size());

            // Now "drag" sphere to a new leaf (simulate split)
            leaf.removeDockable(d2);
            var leaf2 = builder.leaf("sphere-solo");
            leaf2.addDockable(d2);

            var branch = builder.branch("split");
            branch.setOrientation(Orientation.HORIZONTAL);
            branch.addContainers(leaf, leaf2);

            // Capture the branch — should be SplitNode
            var captured2 = captureNode(branch, dockables);
            assertInstanceOf(SplitNode.class, captured2);
            var split = (SplitNode) captured2;
            assertEquals(Orientation.HORIZONTAL, split.orientation());

            // First: single PaneLeaf (only CROSS_SECTION left in original leaf)
            assertInstanceOf(PaneLeaf.class, split.first());
            assertEquals(PaneId.CROSS_SECTION, ((PaneLeaf) split.first()).paneId());

            // Second: single PaneLeaf (SPHERE in new leaf)
            assertInstanceOf(PaneLeaf.class, split.second());
            assertEquals(PaneId.SPHERE, ((PaneLeaf) split.second()).paneId());
        });
    }

    @Test
    void captureSelectedTabIndex() {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            var leaf = builder.leaf("tabs");
            leaf.setPruneWhenEmpty(false);
            var ids = List.of(PaneId.CROSS_SECTION, PaneId.SPHERE, PaneId.TIMELINE);
            for (var id : ids) {
                var d = builder.dockable(id.name());
                d.setTitle(id.title()); d.setNode(new Label(id.name()));
                dockables.put(id, d);
                leaf.addDockable(d);
            }

            // Select the second tab (SPHERE)
            leaf.selectDockable(dockables.get(PaneId.SPHERE));

            var captured = captureNode(leaf, dockables);
            assertInstanceOf(TabNode.class, captured);
            assertEquals(1, ((TabNode) captured).selectedIndex(), "SPHERE is index 1");

            // Select the third tab (TIMELINE)
            leaf.selectDockable(dockables.get(PaneId.TIMELINE));
            var captured2 = captureNode(leaf, dockables);
            assertEquals(2, ((TabNode) captured2).selectedIndex(), "TIMELINE is index 2");
        });
    }

    @Test
    void captureSingleDockableLeafProducesPaneLeafNotTabNode() {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            var leaf = builder.leaf("solo");
            var d = builder.dockable(PaneId.TIMELINE.name());
            d.setTitle("Timeline"); d.setNode(new Label("TL"));
            dockables.put(PaneId.TIMELINE, d);
            leaf.addDockable(d);

            var captured = captureNode(leaf, dockables);
            // Single dockable should produce PaneLeaf, NOT TabNode
            assertInstanceOf(PaneLeaf.class, captured);
            assertEquals(PaneId.TIMELINE, ((PaneLeaf) captured).paneId());
        });
    }

    // --- Restore tests: verify the restored tree matches the original ---

    @Test
    void restoreFromCapturedStateProducesSameStructure() {
        FxTestSupport.runOnFxThread(() -> {
            // Build and capture the default layout
            var original = buildAndCapture();
            var state = new WorkbenchLayoutState(original.captured(), List.of());

            // Restore into a new BentoFX tree
            var bento2 = new Bento();
            var builder2 = bento2.dockBuilding();
            var dockables2 = new EnumMap<PaneId, Dockable>(PaneId.class);

            // Pre-create dockables for all pane IDs (the restore needs them)
            for (var paneId : List.of(PaneId.CROSS_SECTION, PaneId.SPHERE,
                    PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
                    PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE,
                    PaneId.TIMELINE)) {
                var d = builder2.dockable(paneId.name());
                d.setTitle(paneId.title());
                d.setNode(new Label(paneId.name()));
                dockables2.put(paneId, d);
            }

            // Restore the tree
            var restoredContainer = restoreNode(builder2, state.dockRoot(), dockables2);
            assertNotNull(restoredContainer, "Restore should produce a non-null container");

            // Capture the restored tree
            var recaptured = captureNode(restoredContainer, dockables2);
            assertNotNull(recaptured, "Recapture should produce a non-null tree");

            // The recaptured tree should match the original
            assertEquals(original.captured(), recaptured,
                "Captured → serialized → restored → recaptured should be identical");
        });
    }

    @Test
    void restoreRearrangedLayoutRoundTrips() {
        FxTestSupport.runOnFxThread(() -> {
            // Build a non-default layout: two panes side by side + timeline below
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            var leaf1 = builder.leaf("geo");
            var d1 = builder.dockable(PaneId.CROSS_SECTION.name());
            d1.setTitle("Geo"); d1.setNode(new Label("Geo"));
            dockables.put(PaneId.CROSS_SECTION, d1);
            leaf1.addDockable(d1);

            var leaf2 = builder.leaf("sphere");
            var d2 = builder.dockable(PaneId.SPHERE.name());
            d2.setTitle("Sphere"); d2.setNode(new Label("Sphere"));
            dockables.put(PaneId.SPHERE, d2);
            leaf2.addDockable(d2);

            var leaf3 = builder.leaf("tl");
            var d3 = builder.dockable(PaneId.TIMELINE.name());
            d3.setTitle("TL"); d3.setNode(new Label("TL"));
            dockables.put(PaneId.TIMELINE, d3);
            leaf3.addDockable(d3);

            var topSplit = builder.branch("top");
            topSplit.setOrientation(Orientation.HORIZONTAL);
            topSplit.addContainers(leaf1, leaf2);

            var root = builder.branch("root");
            root.setOrientation(Orientation.VERTICAL);
            root.addContainers(topSplit, leaf3);

            // Capture
            var captured = captureNode(root, dockables);

            // Verify structure
            assertInstanceOf(SplitNode.class, captured);
            var vSplit = (SplitNode) captured;
            assertEquals(Orientation.VERTICAL, vSplit.orientation());
            assertInstanceOf(SplitNode.class, vSplit.first()); // horizontal split
            assertInstanceOf(PaneLeaf.class, vSplit.second()); // timeline

            var hSplit = (SplitNode) vSplit.first();
            assertEquals(Orientation.HORIZONTAL, hSplit.orientation());
            assertEquals(PaneId.CROSS_SECTION, ((PaneLeaf) hSplit.first()).paneId());
            assertEquals(PaneId.SPHERE, ((PaneLeaf) hSplit.second()).paneId());

            // Restore
            var bento2 = new Bento();
            var builder2 = bento2.dockBuilding();
            var dockables2 = new EnumMap<PaneId, Dockable>(PaneId.class);
            for (var id : List.of(PaneId.CROSS_SECTION, PaneId.SPHERE, PaneId.TIMELINE)) {
                var d = builder2.dockable(id.name());
                d.setTitle(id.title()); d.setNode(new Label(id.name()));
                dockables2.put(id, d);
            }

            var restoredContainer = restoreNode(builder2, captured, dockables2);
            var recaptured = captureNode(restoredContainer, dockables2);
            assertEquals(captured, recaptured,
                "Rearranged layout should survive capture → restore round-trip");
        });
    }

    // --- Restore helper (mirrors WorkbenchController.restoreNode but testable) ---

    static software.coley.bentofx.layout.DockContainer restoreNode(
            software.coley.bentofx.building.DockBuilding builder,
            DockNode node,
            Map<PaneId, Dockable> dockableMap) {
        if (node instanceof PaneLeaf paneLeaf) {
            var leaf = builder.leaf(paneLeaf.paneId().name().toLowerCase() + "-r");
            leaf.setPruneWhenEmpty(true);
            var d = dockableMap.get(paneLeaf.paneId());
            if (d != null) leaf.addDockable(d);
            return leaf;
        } else if (node instanceof TabNode tabNode) {
            var leaf = builder.leaf("tab-" + System.nanoTime());
            leaf.setPruneWhenEmpty(false);
            for (var tab : tabNode.tabs()) {
                if (tab instanceof PaneLeaf pl) {
                    var d = dockableMap.get(pl.paneId());
                    if (d != null) leaf.addDockable(d);
                }
            }
            if (tabNode.selectedIndex() >= 0 && tabNode.selectedIndex() < leaf.getDockables().size()) {
                leaf.selectDockable(leaf.getDockables().get(tabNode.selectedIndex()));
            }
            return leaf;
        } else if (node instanceof SplitNode splitNode) {
            var branch = builder.branch("split-" + System.nanoTime());
            branch.setOrientation(splitNode.orientation());
            var first = restoreNode(builder, splitNode.first(), dockableMap);
            var second = restoreNode(builder, splitNode.second(), dockableMap);
            if (first != null) branch.addContainer(first);
            if (second != null) branch.addContainer(second);
            // Note: divider position is NOT set here (SplitPane needs layout first)
            return branch;
        }
        return null;
    }

    @Test
    void captureEmptyLeafReturnsNull() {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var leaf = builder.leaf("empty");
            var captured = captureNode(leaf, Map.of());
            assertNull(captured);
        });
    }

    @Test
    void captureBranchWithSingleChildUnwraps() {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            var leaf = builder.leaf("solo");
            var d = builder.dockable(PaneId.TIMELINE.name());
            d.setTitle("TL"); d.setNode(new Label("TL"));
            dockables.put(PaneId.TIMELINE, d);
            leaf.addDockable(d);

            // Branch with only one child — should unwrap to the child
            var branch = builder.branch("wrapper");
            branch.addContainer(leaf);

            var captured = captureNode(branch, dockables);
            assertInstanceOf(PaneLeaf.class, captured);
            assertEquals(PaneId.TIMELINE, ((PaneLeaf) captured).paneId());
        });
    }
}
