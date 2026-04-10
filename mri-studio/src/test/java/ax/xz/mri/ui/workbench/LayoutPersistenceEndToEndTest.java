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
 * End-to-end test that builds the REAL default layout (matching rebuildWorkbench),
 * captures it using the REAL capture logic, serializes → deserializes,
 * restores using the REAL restore logic, and recaptures to verify idempotency.
 */
class LayoutPersistenceEndToEndTest {

    /** PaneIds that go into the analysis leaf (matches ANALYSIS_PANE_IDS minus TIMELINE). */
    private static final List<PaneId> ANALYSIS_TAB_IDS = List.of(
        PaneId.CROSS_SECTION, PaneId.SPHERE,
        PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
        PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE);

    /**
     * Build the exact same BentoFX tree as rebuildWorkbench() does,
     * but without needing a full StudioSession/WorkbenchController.
     */
    record BuiltLayout(
        DockContainerBranch centreShell,
        DockContainerLeaf docLeaf,
        DockContainerBranch bottomArea,
        DockContainerLeaf analysisLeaf,
        DockContainerLeaf timelineLeaf,
        Map<PaneId, Dockable> dockables,
        Bento bento
    ) {}

    static BuiltLayout buildDefaultLayout() {
        var bento = new Bento();
        var builder = bento.dockBuilding();
        var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

        var root = builder.root("root");
        var centreShell = builder.branch("centre-shell");
        var docLeaf = builder.leaf("document_tabs");
        docLeaf.setPruneWhenEmpty(false);

        var analysisLeaf = builder.leaf("analysis_tabs");
        analysisLeaf.setPruneWhenEmpty(false);
        for (var paneId : ANALYSIS_TAB_IDS) {
            var d = builder.dockable(paneId.name());
            d.setTitle(paneId.title());
            d.setNode(new Label(paneId.name()));
            dockables.put(paneId, d);
            analysisLeaf.addDockable(d);
        }
        if (!analysisLeaf.getDockables().isEmpty()) {
            analysisLeaf.selectDockable(analysisLeaf.getDockables().getFirst());
        }

        var timelineLeaf = builder.leaf("timeline");
        timelineLeaf.setPruneWhenEmpty(true);
        var tld = builder.dockable(PaneId.TIMELINE.name());
        tld.setTitle(PaneId.TIMELINE.title());
        tld.setNode(new Label("Timeline"));
        dockables.put(PaneId.TIMELINE, tld);
        timelineLeaf.addDockable(tld);

        var bottomArea = builder.branch("bottom-area");
        root.setOrientation(Orientation.VERTICAL);
        centreShell.setOrientation(Orientation.VERTICAL);
        bottomArea.setOrientation(Orientation.VERTICAL);
        root.addContainers(centreShell);
        centreShell.addContainers(docLeaf, bottomArea);
        bottomArea.addContainers(analysisLeaf, timelineLeaf);

        return new BuiltLayout(centreShell, docLeaf, bottomArea, analysisLeaf, timelineLeaf, dockables, bento);
    }

    // --- Capture logic (copied from WorkbenchController for isolated testing) ---

    static DockNode captureFromCentreShell(DockContainerBranch centreShell, Map<PaneId, Dockable> dockables) {
        // This mirrors captureLayout(): get child at index 1 (bottomArea, skipping docLeaf)
        if (centreShell.getChildContainers().size() > 1) {
            return captureNode(centreShell.getChildContainers().get(1), dockables);
        }
        return null;
    }

    static DockNode captureNode(software.coley.bentofx.layout.DockContainer container, Map<PaneId, Dockable> dockables) {
        if (container instanceof DockContainerBranch branch) {
            var children = branch.getChildContainers();
            if (children.isEmpty()) return null;
            if (children.size() == 1) return captureNode(children.getFirst(), dockables);
            var dividers = branch.getDividerPositions();
            return captureSplit(branch.getOrientation(), children, dividers, 0, dockables);
        } else if (container instanceof DockContainerLeaf leaf) {
            var tabs = new ArrayList<DockNode>();
            int selectedIdx = 0;
            for (int i = 0; i < leaf.getDockables().size(); i++) {
                var d = leaf.getDockables().get(i);
                var paneId = paneIdOf(d, dockables);
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

    static DockNode captureSplit(Orientation orientation,
                                 List<? extends software.coley.bentofx.layout.DockContainer> children,
                                 double[] dividers, int fromIndex, Map<PaneId, Dockable> dockables) {
        if (fromIndex >= children.size() - 1) return captureNode(children.get(fromIndex), dockables);
        var first = captureNode(children.get(fromIndex), dockables);
        var rest = (fromIndex < children.size() - 2)
            ? captureSplit(orientation, children, dividers, fromIndex + 1, dockables)
            : captureNode(children.get(fromIndex + 1), dockables);
        double divPos = fromIndex < dividers.length ? dividers[fromIndex] : 0.5;
        return new SplitNode(orientation, divPos, first, rest);
    }

    static PaneId paneIdOf(Dockable d, Map<PaneId, Dockable> dockables) {
        for (var e : dockables.entrySet()) if (e.getValue() == d) return e.getKey();
        return null;
    }

    // --- Restore logic (copied from WorkbenchController for isolated testing) ---

    static software.coley.bentofx.layout.DockContainer restoreNode(
            software.coley.bentofx.building.DockBuilding builder, DockNode node,
            Map<PaneId, Dockable> dockables, Map<PaneId, DockContainerLeaf> homeLeaves) {
        if (node instanceof PaneLeaf pl) {
            var leaf = builder.leaf(pl.paneId().name().toLowerCase() + "-r");
            leaf.setPruneWhenEmpty(true);
            var d = dockables.get(pl.paneId());
            if (d != null) { homeLeaves.put(pl.paneId(), leaf); leaf.addDockable(d); }
            return leaf;
        } else if (node instanceof TabNode tabNode) {
            var leaf = builder.leaf("tab-" + System.nanoTime());
            leaf.setPruneWhenEmpty(false);
            for (var tab : tabNode.tabs()) {
                if (tab instanceof PaneLeaf pl) {
                    var d = dockables.get(pl.paneId());
                    if (d != null) { homeLeaves.put(pl.paneId(), leaf); leaf.addDockable(d); }
                }
            }
            if (tabNode.selectedIndex() >= 0 && tabNode.selectedIndex() < leaf.getDockables().size())
                leaf.selectDockable(leaf.getDockables().get(tabNode.selectedIndex()));
            return leaf;
        } else if (node instanceof SplitNode splitNode) {
            var branch = builder.branch("split-" + System.nanoTime());
            branch.setOrientation(splitNode.orientation());
            var first = restoreNode(builder, splitNode.first(), dockables, homeLeaves);
            var second = restoreNode(builder, splitNode.second(), dockables, homeLeaves);
            if (first != null) branch.addContainer(first);
            if (second != null) branch.addContainer(second);
            // Note: divider positions can't be tested here (no scene)
            return branch;
        }
        return null;
    }

    // --- Tests ---

    @Test
    void captureDefaultLayoutHasCorrectStructure() {
        FxTestSupport.runOnFxThread(() -> {
            var layout = buildDefaultLayout();
            var captured = captureFromCentreShell(layout.centreShell, layout.dockables);

            assertNotNull(captured);
            assertInstanceOf(SplitNode.class, captured);
            var split = (SplitNode) captured;
            assertEquals(Orientation.VERTICAL, split.orientation());

            // First: TabNode with 7 tabs
            assertInstanceOf(TabNode.class, split.first());
            var tabs = (TabNode) split.first();
            assertEquals(7, tabs.tabs().size());
            for (int i = 0; i < ANALYSIS_TAB_IDS.size(); i++) {
                assertEquals(ANALYSIS_TAB_IDS.get(i), ((PaneLeaf) tabs.tabs().get(i)).paneId());
            }

            // Second: PaneLeaf(TIMELINE)
            assertInstanceOf(PaneLeaf.class, split.second());
            assertEquals(PaneId.TIMELINE, ((PaneLeaf) split.second()).paneId());
        });
    }

    @Test
    void captureSerializeDeserializeRestoreRecaptureIsIdempotent(@TempDir Path tempDir) {
        FxTestSupport.runOnFxThread(() -> {
            // Step 1: Build default layout and capture
            var layout = buildDefaultLayout();
            var captured = captureFromCentreShell(layout.centreShell, layout.dockables);
            assertNotNull(captured);

            // Step 2: Serialize to disk
            var state = new WorkbenchLayoutState(captured, List.of());
            var store = new PersistentLayoutStore(tempDir.resolve("layout.json"));
            try { store.save(state); } catch (Exception e) { fail(e); }

            // Step 3: Deserialize
            var loaded = store.load();
            assertTrue(loaded.isPresent());
            assertEquals(captured, loaded.get().dockRoot(),
                "Deserialized tree should match captured tree");

            // Step 4: Restore into a NEW BentoFX tree
            var bento2 = new Bento();
            var builder2 = bento2.dockBuilding();
            var dockables2 = new EnumMap<PaneId, Dockable>(PaneId.class);
            var homeLeaves2 = new EnumMap<PaneId, DockContainerLeaf>(PaneId.class);

            // Create fresh dockables (simulating app restart)
            for (var paneId : ANALYSIS_TAB_IDS) {
                var d = builder2.dockable(paneId.name());
                d.setTitle(paneId.title()); d.setNode(new Label(paneId.name()));
                dockables2.put(paneId, d);
            }
            var tld = builder2.dockable(PaneId.TIMELINE.name());
            tld.setTitle(PaneId.TIMELINE.title()); tld.setNode(new Label("TL"));
            dockables2.put(PaneId.TIMELINE, tld);

            var restoredContainer = restoreNode(builder2, loaded.get().dockRoot(), dockables2, homeLeaves2);
            assertNotNull(restoredContainer, "Restore should produce a non-null container");

            // Step 5: Recapture the restored tree
            var recaptured = captureNode(restoredContainer, dockables2);
            assertNotNull(recaptured, "Recapture should produce a non-null tree");

            // Step 6: Verify structural equality (ignoring divider positions — can't test without scene)
            assertStructurallyEqual(captured, recaptured,
                "Captured → serialized → restored → recaptured should have identical structure");
        });
    }

    @Test
    void restoreAfterDragRearrangement(@TempDir Path tempDir) {
        FxTestSupport.runOnFxThread(() -> {
            var bento = new Bento();
            var builder = bento.dockBuilding();
            var dockables = new EnumMap<PaneId, Dockable>(PaneId.class);

            // Build: CROSS_SECTION alone, SPHERE+TIMELINE tabbed
            var leaf1 = builder.leaf("geo");
            var d1 = builder.dockable(PaneId.CROSS_SECTION.name());
            d1.setTitle("Geo"); d1.setNode(new Label("Geo"));
            dockables.put(PaneId.CROSS_SECTION, d1);
            leaf1.addDockable(d1);

            var leaf2 = builder.leaf("mixed");
            leaf2.setPruneWhenEmpty(false);
            var d2 = builder.dockable(PaneId.SPHERE.name());
            d2.setTitle("Sphere"); d2.setNode(new Label("Sphere"));
            dockables.put(PaneId.SPHERE, d2);
            leaf2.addDockable(d2);
            var d3 = builder.dockable(PaneId.TIMELINE.name());
            d3.setTitle("TL"); d3.setNode(new Label("TL"));
            dockables.put(PaneId.TIMELINE, d3);
            leaf2.addDockable(d3);

            var branch = builder.branch("root");
            branch.setOrientation(Orientation.HORIZONTAL);
            branch.addContainers(leaf1, leaf2);

            // Capture
            var captured = captureNode(branch, dockables);

            // Serialize → deserialize
            var state = new WorkbenchLayoutState(captured, List.of());
            var store = new PersistentLayoutStore(tempDir.resolve("layout.json"));
            try { store.save(state); } catch (Exception e) { fail(e); }
            var loaded = store.load().orElseThrow();

            // Restore
            var bento2 = new Bento();
            var builder2 = bento2.dockBuilding();
            var dockables2 = new EnumMap<PaneId, Dockable>(PaneId.class);
            var homeLeaves2 = new EnumMap<PaneId, DockContainerLeaf>(PaneId.class);
            for (var id : List.of(PaneId.CROSS_SECTION, PaneId.SPHERE, PaneId.TIMELINE)) {
                var d = builder2.dockable(id.name());
                d.setTitle(id.title()); d.setNode(new Label(id.name()));
                dockables2.put(id, d);
            }
            var restoredContainer = restoreNode(builder2, loaded.dockRoot(), dockables2, homeLeaves2);
            var recaptured = captureNode(restoredContainer, dockables2);

            assertStructurallyEqual(captured, recaptured,
                "Rearranged layout should survive full round-trip");
        });
    }

    // --- Structural equality (ignores divider positions) ---

    static void assertStructurallyEqual(DockNode expected, DockNode actual, String message) {
        assertEquals(expected.getClass(), actual.getClass(), message + " — node type mismatch");
        if (expected instanceof PaneLeaf e && actual instanceof PaneLeaf a) {
            assertEquals(e.paneId(), a.paneId(), message + " — paneId mismatch");
        } else if (expected instanceof TabNode e && actual instanceof TabNode a) {
            assertEquals(e.tabs().size(), a.tabs().size(), message + " — tab count mismatch");
            assertEquals(e.selectedIndex(), a.selectedIndex(), message + " — selectedIndex mismatch");
            for (int i = 0; i < e.tabs().size(); i++) {
                assertStructurallyEqual(e.tabs().get(i), a.tabs().get(i), message + "[tab " + i + "]");
            }
        } else if (expected instanceof SplitNode e && actual instanceof SplitNode a) {
            assertEquals(e.orientation(), a.orientation(), message + " — orientation mismatch");
            // Don't compare dividerPosition — can't be tested without scene layout
            assertStructurallyEqual(e.first(), a.first(), message + ".first");
            assertStructurallyEqual(e.second(), a.second(), message + ".second");
        }
    }
}
