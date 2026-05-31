package ax.xz.mri.support;

/**
 * Helpers for writing short eigenfield scripts in tests without spelling
 * out the full {@code import module / class … implements EigenfieldScript}
 * boilerplate every time. {@link #wrap(String)} turns
 * {@code "return Vec3.of(0, 0, 1);"} into the canonical full-class source
 * the {@link ax.xz.mri.dsl.EigenfieldEngine} expects.
 *
 * <p>Production starter sources use the full-class form directly so the
 * editor's "Reset to template" flow lands on a syntactically idiomatic
 * source. This helper exists purely to keep test fixtures terse.
 */
public final class EigenfieldScripts {
    private EigenfieldScripts() {}

    /** Wrap a {@code return Vec3.…} body into the minimal compilable class form. */
    public static String wrap(String body) {
        return """
            import module ax.xz.mri;
            import static java.lang.Math.*;
            class S implements EigenfieldScript {
                public Vec3 evaluate(double x, double y, double z) {
                    %s
                }
            }
            """.formatted(body.strip());
    }
}
