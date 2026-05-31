package ax.xz.mri.model.substance;

/**
 * A named starter template shown in the New-Substance wizard.
 *
 * <p>Starters are UI-only affordances — they exist so users can seed a new
 * substance document with a sensible default instead of being dropped into
 * an empty form. Once chosen, {@link #template()} is copied verbatim into
 * the new {@link ax.xz.mri.project.SubstanceDocument}; the starter's
 * identity is not retained anywhere in the persisted data model.
 */
public record SubstanceStarter(
    String id,
    String name,
    String description,
    Substance template
) {
    public SubstanceStarter {
        if (id == null || id.isBlank())   throw new IllegalArgumentException("SubstanceStarter.id must be non-blank");
        if (name == null)                  throw new IllegalArgumentException("SubstanceStarter.name must be non-null");
        if (description == null) description = "";
        if (template == null)              throw new IllegalArgumentException("SubstanceStarter.template must be non-null");
    }
}
