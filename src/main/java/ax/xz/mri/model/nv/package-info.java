/**
 * NV-centre geometry + physics primitives shared by every NV-based
 * substance. Pure data — no behaviour beyond record validation and
 * deterministic layout generation.
 *
 * <ul>
 *   <li>{@link ax.xz.mri.model.nv.NvCentre} — one NV at a position
 *       with a quantisation axis.</li>
 *   <li>{@link ax.xz.mri.model.nv.NvAxis} — the four diamond-lattice
 *       [111] directions plus their negatives (the eight crystallographic
 *       NV orientations).</li>
 *   <li>{@link ax.xz.mri.model.nv.NvArrayShape} +
 *       {@link ax.xz.mri.model.nv.NvArrayGeometry} — the named layout
 *       generator (linear-random / linear-uniform / 2D grid / custom)
 *       and the parameters it expands into a deterministic list of
 *       {@link ax.xz.mri.model.nv.NvCentre}s.</li>
 *   <li>{@link ax.xz.mri.model.nv.NvPhysics} — gyromagnetic ratio, bias
 *       B0, homogeneous T2, PL contrast, polarisation efficiency.</li>
 * </ul>
 *
 * <p>Procedure-side concerns (sample fields, readout-mode toggles, GP
 * priors, action scoring) live next to the procedure starters that
 * own them, not here. The {@link ax.xz.mri.model.substance.NvEnsemble}
 * substance composes an {@link ax.xz.mri.model.nv.NvArrayGeometry}
 * with {@link ax.xz.mri.model.nv.NvPhysics} into a project-persistable
 * spin collection.
 */
package ax.xz.mri.model.nv;
