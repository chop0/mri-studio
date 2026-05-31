package ax.xz.mri.ui.time;

/**
 * Façade bundling the four time-axis sub-objects plus a generation counter.
 * Each sub-object owns its own clamping; the façade just exposes them and
 * provides snapshot/restore for project save/restore.
 *
 * <p>Snapshot/restore is bounds-first: {@code maxTime} is restored before the
 * cursor/viewport/analysis values, so they don't get clamped against the wrong
 * bound on the way in.
 */
public final class TimeAxis {
    public static final double ZOOM_IN_FACTOR = 0.8;
    public static final double ZOOM_OUT_FACTOR = 1.25;

    public final Domain domain = new Domain();
    public final Cursor cursor = new Cursor(domain);
    public final Viewport viewport = new Viewport(domain);
    public final AnalysisWindow analysis = new AnalysisWindow(domain);
    public final Generation generation = new Generation();

    public void snapAnalysisToViewport() {
        analysis.setSpan(viewport.start.get(), viewport.end.get());
    }

    public void snapViewportToAnalysis() {
        viewport.setSpan(analysis.start.get(), analysis.end.get());
    }

    public void resetToFullRange() {
        viewport.fit();
        analysis.fit();
        cursor.scrubTo(domain.maxTime() / 2.0);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            domain.maxTime.get(),
            cursor.time.get(),
            viewport.start.get(), viewport.end.get(),
            analysis.start.get(), analysis.end.get()
        );
    }

    public void restore(Snapshot snap) {
        domain.maxTime.set(snap.maxTime());
        viewport.setSpan(snap.viewportStart(), snap.viewportEnd());
        analysis.setSpan(snap.analysisStart(), snap.analysisEnd());
        cursor.scrubTo(snap.cursor());
    }

    public record Snapshot(
        double maxTime,
        double cursor,
        double viewportStart, double viewportEnd,
        double analysisStart, double analysisEnd
    ) {}
}
