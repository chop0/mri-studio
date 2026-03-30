package ax.xz.mri.state;

/**
 * Composition root for all observable application state.
 * The only class that wires cross-state dependencies.
 */
public class AppState {
    public final DocumentState     document    = new DocumentState();
    public final ViewportState     viewport    = new ViewportState();
    public final CameraState       camera      = new CameraState();
    public final IsochromatState   isochromats = new IsochromatState();
    public final CrossSectionState crossSection = new CrossSectionState();
    public final ComputedState     computed    = new ComputedState();

    public AppState() {
        // When the pulse changes, propagate to isochromats and computed state
        document.currentPulse.addListener((obs, old, pulse) -> {
            var data = document.blochData.get();
            isochromats.setContext(data, pulse);
            isochromats.resimulateAll();
            computed.recompute(data, pulse);
        });

        // Update maxTime when data changes
        document.blochData.addListener((obs, old, data) -> {
            if (data == null || data.field() == null) return;
            double total = data.field().segments.stream()
                .mapToDouble(s -> s.durationMicros()).sum();
            viewport.maxTime.set(total);
            viewport.resetToFullRange();
        });

        // Sync signal trace into viewport for timeline rendering
        computed.signalTrace.addListener((obs, old, sig) -> { /* panes subscribe directly */ });
    }
}
