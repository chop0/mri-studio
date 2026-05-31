package ax.xz.mri.dsl;

/**
 * Named handle for a probe inside a {@link ax.xz.mri.model.simulation.MultiProbeSignalTrace}.
 *
 * <p>Replaces ad-hoc {@code trace.byProbe().get("Red counter")} string lookups
 * with a typed value scripts can pass around and use to disambiguate probes.
 * {@link ax.xz.mri.dsl.ScriptContext#probe(String)} validates the name against
 * the active simulation; {@link #of(String)} is the unvalidated escape hatch.
 *
 * <p>The key is intentionally a thin wrapper over the probe's display name —
 * the source of truth for probe identity is still the circuit document, so
 * adopting a richer key would have meant duplicating that identity in the
 * script API. The {@link String} name + record-style {@code equals} is enough
 * to give scripts a typed-handle ergonomic without forcing a parallel
 * identity scheme.
 */
public record ProbeKey(String name) {

    public ProbeKey {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("ProbeKey name must not be blank");
    }

    /** Builds a {@code ProbeKey} without validating against any simulation. */
    public static ProbeKey of(String name) { return new ProbeKey(name); }

    @Override public String toString() { return "ProbeKey(" + name + ")"; }
}
