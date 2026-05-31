package ax.xz.mri.ui.edit;

import ax.xz.mri.ui.model.IsochromatId;
import ax.xz.mri.ui.model.IsochromatSelectionModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link Selectable} contract — the same invariants every selectable
 * surface (clips, isochromat points, schematic components, project tree)
 * shares. Run against the two existing impls so a future regression in
 * either trips here first.
 */
class SelectableContractTest {

    @Test
    void selectionModelHonoursContract() {
        verify(new SelectionModel(), "a", "b", "c");
    }

    @Test
    void isochromatSelectionModelHonoursContract() {
        verify(new IsochromatSelectionModel(),
            new IsochromatId(1), new IsochromatId(2), new IsochromatId(3));
    }

    private static <T> void verify(Selectable<T> s, T a, T b, T c) {
        // Initial: empty + null primary.
        assertTrue(s.selected().isEmpty());
        assertNull(s.primary().get());

        // selectOnly establishes primary.
        s.selectOnly(a);
        assertTrue(s.isSelected(a));
        assertTrue(s.isPrimary(a));
        assertEquals(1, s.selected().size());

        // add extends without unsetting primary.
        s.add(b);
        assertTrue(s.isSelected(b));
        assertTrue(s.isPrimary(a));

        // toggle removes; primary re-anchors.
        s.toggle(a);
        assertFalse(s.isSelected(a));
        assertTrue(s.isSelected(b));
        // Primary must still be a member of the live set.
        assertTrue(s.selected().contains(s.primary().get()));

        // toggle re-adds; new addition becomes primary.
        s.toggle(c);
        assertTrue(s.isSelected(c));
        assertTrue(s.isPrimary(c));

        // clear empties everything.
        s.clear();
        assertTrue(s.selected().isEmpty());
        assertNull(s.primary().get());
    }
}
