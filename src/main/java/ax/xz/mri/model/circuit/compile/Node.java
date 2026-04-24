package ax.xz.mri.model.circuit.compile;

/**
 * Opaque handle to an MNA node that a {@link CircuitStampContext} hands out
 * when a component's {@code stamp} method asks for a port by name. Ground is
 * represented by {@link #GROUND} (index {@code -1}); every other node has a
 * dense non-negative index into the MNA matrix.
 *
 * <p>The record is a value type on purpose: components receive these from the
 * context, pass them back into stamp calls, and never need to know the
 * numeric index themselves.
 */
public record Node(int index) {
    public static final Node GROUND = new Node(-1);

    public boolean isGround() {
        return index < 0;
    }
}
