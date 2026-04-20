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
 * <p>{@code units} is the default units string for the amplitude scalar of
 * fields created from this starter (e.g. {@code "T"}, {@code "T/m"}).
 */
public record EigenfieldStarter(
    String id,
    String name,
    String description,
    String source,
    String units
) {
}
