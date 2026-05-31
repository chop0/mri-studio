package ax.xz.mri.ui.model;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.support.TestSimulationFactory;
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
    void resetAddMoveDuplicateAndRemoveMaintainStableIds() {
        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(TestSimulationFactory.sampleSimulation(), TestSimulationFactory.pulseA());

        points.resetToDefaults();
        int defaults = points.entries.size();
        assertTrue(defaults > 0);
        assertTrue(points.entries.stream().allMatch(entry -> entry.trajectory() != null));

        // Adding a user point uses a Vec3 in metres. The collection model
        // doesn't know or care about cylindrical symmetry — positions are
        // arbitrary 3-D points in the lab frame.
        var p = new Vec3(1e-3, 0, 8e-3);
        points.addUserPoint(p, "User Point");
        var userId = selection.primarySelectedId.get();
        var user = points.findById(userId).orElseThrow();
        assertEquals(p, user.position());
        assertNotNull(user.trajectory());

        // Move the user point — still a Vec3 in metres.
        var moved = new Vec3(1e-3, 0, 0);
        points.move(userId, moved);
        user = points.findById(userId).orElseThrow();
        assertEquals(moved, user.position());

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
