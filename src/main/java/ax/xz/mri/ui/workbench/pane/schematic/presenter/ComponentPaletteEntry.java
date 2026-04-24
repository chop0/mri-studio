package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;

import java.util.function.Supplier;

/**
 * One entry in the left-hand component palette (and, by extension, the
 * right-click "Add …" menu on the canvas). Built by
 * {@link ComponentPresenters#paletteEntries()} — the UI code iterates them
 * generically, so new component kinds appear automatically when their
 * entry is added to the registry.
 *
 * <p>{@link #factory()} produces a fresh component with a unique id each
 * time; the name may collide with existing components but the editing
 * session de-duplicates before accepting.
 */
public record ComponentPaletteEntry(
    String section,
    String label,
    String tooltip,
    Supplier<CircuitComponent> factory
) {
    public ComponentPaletteEntry {
        if (section == null || section.isBlank()) throw new IllegalArgumentException("section required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label required");
        if (factory == null) throw new IllegalArgumentException("factory required");
    }
}
