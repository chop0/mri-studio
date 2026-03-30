package ax.xz.mri.ui.model;

/** Stable identity for a point/isochromat within one studio session. */
public record IsochromatId(long value) implements Comparable<IsochromatId> {
    @Override
    public int compareTo(IsochromatId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "iso-" + value;
    }
}
