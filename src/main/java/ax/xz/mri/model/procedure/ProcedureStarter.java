package ax.xz.mri.model.procedure;

/**
 * A named starter procedure shown in the New-Procedure wizard.
 *
 * <p>Mirrors {@link ax.xz.mri.model.simulation.dsl.EigenfieldStarter} in
 * shape and intent: the starter's identity is never stored in the document;
 * the user's edited source is the source of truth from the moment they hit
 * Finish.
 */
public record ProcedureStarter(
    String id,
    String name,
    String description,
    String source
) {
    public ProcedureStarter {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("ProcedureStarter.id must not be blank");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("ProcedureStarter.source must not be blank");
    }
}
