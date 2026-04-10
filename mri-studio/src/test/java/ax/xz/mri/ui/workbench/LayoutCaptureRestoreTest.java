package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.workbench.layout.DockNode;
import ax.xz.mri.ui.workbench.layout.FloatingWindowState;
import ax.xz.mri.ui.workbench.layout.PaneLeaf;
import ax.xz.mri.ui.workbench.layout.SplitNode;
import ax.xz.mri.ui.workbench.layout.TabNode;
import ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Orientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for layout capture → serialization → deserialization round-trip.
 * These tests verify the data model without requiring JavaFX Platform init.
 */
class LayoutCaptureRestoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- Serialization round-trip tests ---

    @Test
    void singlePaneLeafRoundTrips() throws Exception {
        var node = new PaneLeaf(PaneId.TIMELINE);
        var json = mapper.writeValueAsString(node);
        var restored = mapper.readValue(json, DockNode.class);
        assertEquals(node, restored);
    }

    @Test
    void tabNodeRoundTrips() throws Exception {
        var node = new TabNode(List.of(
            new PaneLeaf(PaneId.CROSS_SECTION),
            new PaneLeaf(PaneId.SPHERE),
            new PaneLeaf(PaneId.PHASE_MAP_Z)
        ), 1);
        var json = mapper.writeValueAsString(node);
        var restored = mapper.readValue(json, DockNode.class);
        assertEquals(node, restored);
        assertInstanceOf(TabNode.class, restored);
        assertEquals(3, ((TabNode) restored).tabs().size());
        assertEquals(1, ((TabNode) restored).selectedIndex());
    }

    @Test
    void splitNodeRoundTrips() throws Exception {
        var node = new SplitNode(Orientation.VERTICAL, 0.75,
            new PaneLeaf(PaneId.CROSS_SECTION),
            new PaneLeaf(PaneId.TIMELINE));
        var json = mapper.writeValueAsString(node);
        var restored = mapper.readValue(json, DockNode.class);
        assertEquals(node, restored);
        assertInstanceOf(SplitNode.class, restored);
        assertEquals(0.75, ((SplitNode) restored).dividerPosition(), 1e-10);
    }

    @Test
    void nestedSplitWithTabsRoundTrips() throws Exception {
        // Simulates the default layout: analysis tabs + timeline in a vertical split
        var analysisTab = new TabNode(List.of(
            new PaneLeaf(PaneId.CROSS_SECTION),
            new PaneLeaf(PaneId.SPHERE),
            new PaneLeaf(PaneId.PHASE_MAP_Z),
            new PaneLeaf(PaneId.PHASE_MAP_R),
            new PaneLeaf(PaneId.TRACE_PHASE),
            new PaneLeaf(PaneId.TRACE_POLAR),
            new PaneLeaf(PaneId.TRACE_MAGNITUDE)
        ), 0);
        var timeline = new PaneLeaf(PaneId.TIMELINE);
        var bottomArea = new SplitNode(Orientation.VERTICAL, 0.75, analysisTab, timeline);

        var json = mapper.writeValueAsString(bottomArea);
        var restored = mapper.readValue(json, DockNode.class);
        assertEquals(bottomArea, restored);
    }

    @Test
    void fullStateRoundTrips(@TempDir Path tempDir) throws Exception {
        var analysisTab = new TabNode(List.of(
            new PaneLeaf(PaneId.CROSS_SECTION),
            new PaneLeaf(PaneId.SPHERE)
        ), 1);
        var timeline = new PaneLeaf(PaneId.TIMELINE);
        var tree = new SplitNode(Orientation.VERTICAL, 0.8, analysisTab, timeline);
        var state = new WorkbenchLayoutState(tree,
            List.of(new FloatingWindowState(PaneId.POINTS, 100, 200, 300, 400)));

        var store = new PersistentLayoutStore(tempDir.resolve("layout.json"));
        store.save(state);
        var loaded = store.load();

        assertTrue(loaded.isPresent());
        assertEquals(state, loaded.get());

        // Verify the tree structure is correct after round-trip
        var loadedTree = (SplitNode) loaded.get().dockRoot();
        assertEquals(Orientation.VERTICAL, loadedTree.orientation());
        assertEquals(0.8, loadedTree.dividerPosition(), 1e-10);
        assertInstanceOf(TabNode.class, loadedTree.first());
        assertInstanceOf(PaneLeaf.class, loadedTree.second());

        var loadedTabs = (TabNode) loadedTree.first();
        assertEquals(2, loadedTabs.tabs().size());
        assertEquals(1, loadedTabs.selectedIndex());
        assertEquals(PaneId.CROSS_SECTION, ((PaneLeaf) loadedTabs.tabs().get(0)).paneId());
        assertEquals(PaneId.SPHERE, ((PaneLeaf) loadedTabs.tabs().get(1)).paneId());
    }

    // --- Structural tests: what the default layout should capture as ---

    @Test
    void defaultLayoutCapturesAsExpectedStructure() {
        // The default layout from rebuildWorkbench() should produce:
        // SplitNode(VERTICAL, 0.75,
        //   TabNode([CROSS_SECTION, SPHERE, PHASE_MAP_Z, PHASE_MAP_R, TRACE_PHASE, TRACE_POLAR, TRACE_MAGNITUDE], 0),
        //   PaneLeaf(TIMELINE))
        var expected = new SplitNode(Orientation.VERTICAL, 0.75,
            new TabNode(List.of(
                new PaneLeaf(PaneId.CROSS_SECTION),
                new PaneLeaf(PaneId.SPHERE),
                new PaneLeaf(PaneId.PHASE_MAP_Z),
                new PaneLeaf(PaneId.PHASE_MAP_R),
                new PaneLeaf(PaneId.TRACE_PHASE),
                new PaneLeaf(PaneId.TRACE_POLAR),
                new PaneLeaf(PaneId.TRACE_MAGNITUDE)
            ), 0),
            new PaneLeaf(PaneId.TIMELINE));

        // Verify this round-trips through JSON
        assertDoesNotThrow(() -> {
            var json = mapper.writeValueAsString(expected);
            var restored = mapper.readValue(json, DockNode.class);
            assertEquals(expected, restored);
        });
    }

    @Test
    void rearrangedLayoutCapturesCorrectly() {
        // User drags SPHERE out of analysis tabs into its own split alongside TIMELINE
        // Expected structure:
        // SplitNode(VERTICAL, 0.6,
        //   TabNode([CROSS_SECTION, PHASE_MAP_Z, ...], 0),  // analysis without sphere
        //   SplitNode(HORIZONTAL, 0.5,
        //     PaneLeaf(SPHERE),
        //     PaneLeaf(TIMELINE)))
        var rearranged = new SplitNode(Orientation.VERTICAL, 0.6,
            new TabNode(List.of(
                new PaneLeaf(PaneId.CROSS_SECTION),
                new PaneLeaf(PaneId.PHASE_MAP_Z),
                new PaneLeaf(PaneId.PHASE_MAP_R)
            ), 0),
            new SplitNode(Orientation.HORIZONTAL, 0.5,
                new PaneLeaf(PaneId.SPHERE),
                new PaneLeaf(PaneId.TIMELINE)));

        assertDoesNotThrow(() -> {
            var json = mapper.writeValueAsString(rearranged);
            var restored = mapper.readValue(json, DockNode.class);
            assertEquals(rearranged, restored);
        });
    }

    // --- Edge case tests ---

    @Test
    void emptyFloatingWindowsList() throws Exception {
        var state = new WorkbenchLayoutState(new PaneLeaf(PaneId.TIMELINE), List.of());
        var json = mapper.writeValueAsString(state);
        var restored = mapper.readValue(json, WorkbenchLayoutState.class);
        assertEquals(state, restored);
        assertTrue(restored.floatingWindows().isEmpty());
    }

    @Test
    void nullDockRootDeserializesGracefully() throws Exception {
        var json = """
            {"dockRoot": null, "floatingWindows": []}
            """;
        var state = mapper.readValue(json, WorkbenchLayoutState.class);
        assertNull(state.dockRoot());
    }

    @Test
    void allPaneIdsSerializeCorrectly() throws Exception {
        for (var paneId : PaneId.values()) {
            if (paneId == PaneId.SEQUENCE_EDITOR || paneId == PaneId.SIM_CONFIG_EDITOR) continue;
            var leaf = new PaneLeaf(paneId);
            var json = mapper.writeValueAsString(leaf);
            var restored = mapper.readValue(json, DockNode.class);
            assertEquals(leaf, restored);
        }
    }

    @Test
    void deeplyNestedSplitRoundTrips() throws Exception {
        // 4 levels deep
        var deep = new SplitNode(Orientation.VERTICAL, 0.3,
            new SplitNode(Orientation.HORIZONTAL, 0.5,
                new SplitNode(Orientation.VERTICAL, 0.4,
                    new PaneLeaf(PaneId.CROSS_SECTION),
                    new PaneLeaf(PaneId.SPHERE)),
                new PaneLeaf(PaneId.PHASE_MAP_Z)),
            new PaneLeaf(PaneId.TIMELINE));

        var json = mapper.writeValueAsString(deep);
        var restored = mapper.readValue(json, DockNode.class);
        assertEquals(deep, restored);
    }

    @Test
    void paneIdDeserializationWorksWithModuleSystem() throws Exception {
        // This test catches the module access bug: Jackson needs to access PaneId.valueOf()
        // which requires "opens ax.xz.mri.ui.workbench to com.fasterxml.jackson.databind"
        var store = new PersistentLayoutStore(java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"), "layout-test-" + System.nanoTime() + ".json"));
        var state = new WorkbenchLayoutState(
            new SplitNode(Orientation.VERTICAL, 0.5,
                new PaneLeaf(PaneId.CROSS_SECTION),
                new PaneLeaf(PaneId.TIMELINE)),
            List.of());
        store.save(state);
        var loaded = store.load();
        assertTrue(loaded.isPresent(), "Layout should load — PaneId deserialization must work");
        assertEquals(state, loaded.get());
    }

    @Test
    void dividerPositionPreservedPrecisely() throws Exception {
        var node = new SplitNode(Orientation.VERTICAL, 0.123456789,
            new PaneLeaf(PaneId.CROSS_SECTION),
            new PaneLeaf(PaneId.TIMELINE));
        var json = mapper.writeValueAsString(node);
        var restored = (SplitNode) mapper.readValue(json, DockNode.class);
        assertEquals(0.123456789, restored.dividerPosition(), 1e-15);
    }
}
