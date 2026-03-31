package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.workbench.layout.FloatingWindowState;
import ax.xz.mri.ui.workbench.layout.PaneLeaf;
import ax.xz.mri.ui.workbench.layout.SplitNode;
import ax.xz.mri.ui.workbench.layout.TabNode;
import ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState;
import javafx.geometry.Orientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentLayoutStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripTheDockTreeAndFloatingWindows() throws Exception {
        var store = new PersistentLayoutStore(tempDir.resolve("layout.json"));
        var state = new WorkbenchLayoutState(
            new SplitNode(
                Orientation.HORIZONTAL,
                0.42,
                new PaneLeaf(PaneId.SPHERE),
                new TabNode(List.of(
                    new PaneLeaf(PaneId.CROSS_SECTION),
                    new PaneLeaf(PaneId.TIMELINE)
                ), 1)
            ),
            List.of(new FloatingWindowState(PaneId.POINTS, 20, 30, 640, 420))
        );

        store.save(state);

        var loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(state, loaded.get());
    }

    @Test
    void loadReturnsEmptyWhenStoredLayoutIsCorrupt() throws Exception {
        var path = tempDir.resolve("layout.json");
        Files.writeString(path, "{ definitely not json");

        var store = new PersistentLayoutStore(path);

        assertTrue(store.load().isEmpty());
    }
}
