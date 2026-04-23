package ax.xz.mri.model.simulation.dsl;

/**
 * A named starter script shown in the New-Eigenfield wizard.
 *
 * <p>Starters are UI-only affordances — they exist so users can seed a new
 * eigenfield document with a reasonable template instead of starting from a
 * blank editor. Once chosen, the starter's source is copied verbatim into the
 * new {@link ax.xz.mri.project.EigenfieldDocument}; the starter's identity is
 * not retained anywhere in the data model.
 *
 * <p>{@code units} and {@code defaultMagnitude} initialise the eigenfield's
 * physical-calibration metadata (see
 * {@link ax.xz.mri.project.EigenfieldDocument}).
 */
public record EigenfieldStarter(
    String id,
    String name,
    String description,
    String source,
    String units,
    double defaultMagnitude
) {
    public EigenfieldStarter {
        if (units == null) throw new IllegalArgumentException("EigenfieldStarter.units must not be null");
        if (!(defaultMagnitude > 0))
            throw new IllegalArgumentException("EigenfieldStarter.defaultMagnitude must be positive, got " + defaultMagnitude);
    }
}
