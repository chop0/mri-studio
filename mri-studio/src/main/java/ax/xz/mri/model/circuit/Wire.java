package ax.xz.mri.model.circuit;

/**
 * A connection between two component terminals.
 *
 * <p>Wires are undirected — {@link #from()} and {@link #to()} are equivalent
 * for circuit-solving purposes. The stable {@link #id()} is just so the UI
 * can reference a specific wire under selection.
 */
public record Wire(String id, ComponentTerminal from, ComponentTerminal to) {
    public Wire {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Wire.id must be non-blank");
        if (from == null || to == null) throw new IllegalArgumentException("Wire endpoints must not be null");
        if (from.equals(to)) throw new IllegalArgumentException("Wire endpoints must differ (got " + from + ")");
    }
}
