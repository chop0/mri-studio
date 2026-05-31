package ax.xz.mri.model.nv;

/**
 * One NV centre at a known position with a known axis orientation.
 *
 * <p>Masking individual NVs is done by excluding them from the array list,
 * not by reducing a per-NV weight — there's no physical interpretation for
 * a fractional contribution to summed PL.
 */
public record NvCentre(double xMetres, double yMetres, double zMetres, NvAxis axis) {
    public NvCentre {
        if (axis == null) throw new IllegalArgumentException("NvCentre.axis must be non-null");
        if (!Double.isFinite(xMetres) || !Double.isFinite(yMetres) || !Double.isFinite(zMetres)) {
            throw new IllegalArgumentException("NvCentre coordinates must be finite");
        }
    }
}
