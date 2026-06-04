/**
 * Compile-check module for the bundled procedure-starter templates.
 *
 * <p>These are the exact sources the studio ships as runtime DSL templates: the
 * {@code copyStarterSources} task in the root build copies them into
 * {@code mri-studio}'s resources (stripping the {@code package} line the
 * default-package DSL convention requires). Holding them here as real,
 * {@code package}d Java means {@code javac} type-checks every starter against
 * {@code ax.xz.mri}'s exported DSL surface on each build — a renamed method or
 * a changed signature fails the build instead of a user's run.
 *
 * <p>The module is never shipped or required by anything; only its
 * {@code compileJava} matters.
 */
module ax.xz.mri.starters {
    requires ax.xz.mri;
}
