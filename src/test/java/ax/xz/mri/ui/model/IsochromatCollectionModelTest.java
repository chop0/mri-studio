package ax.xz.mri.ui.model;

import ax.xz.mri.support.TestSimulationOutputFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsochromatCollectionModelTest {
    @Test
    void resetAddMoveDuplicateAndRemoveMaintainStableIdsAndSliceMembership() {
        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(TestSimulationOutputFactory.sampleDocument(), TestSimulationOutputFactory.pulseA());

        points.resetToDefaults();
        int defaults = points.entries.size();
        assertTrue(defaults > 0);
        assertTrue(points.entries.stream().allMatch(entry -> entry.trajectory() != null));

        points.addUserPoint(1.0, 8.0, "User Point");
        var userId = selection.primarySelectedId.get();
        var user = points.findById(userId).orElseThrow();
        assertFalse(user.inSlice());
        assertNotNull(user.trajectory());

        points.move(userId, 1.0, 0.0);
        user = points.findById(userId).orElseThrow();
        assertTrue(user.inSlice());

        selection.setSingle(userId);
        points.duplicateSelected();
        assertEquals(defaults + 2, points.entries.size());
        var duplicate = points.entries.stream()
            .filter(entry -> entry.name().equals("User Point copy"))
            .findFirst()
            .orElseThrow();
        assertNotEquals(userId, duplicate.id());
        assertEquals(List.of(duplicate.id()), List.copyOf(selection.selectedIds));

        selection.setSingle(userId);
        points.remove(userId);
        assertTrue(points.findById(userId).isEmpty());
        assertFalse(selection.selectedIds.contains(userId));
        assertNull(selection.primarySelectedId.get());
    }
}
