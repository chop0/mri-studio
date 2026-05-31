package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the RF-active spans of a simulation pulse — used as background
 * highlights on scrub strips in trace panes.
 *
 * <p>Walks the simulation's segment list in lock-step with the authored
 * pulse to figure out which absolute time ranges had the RF carrier on.
 */
public final class RfSpans {
    private RfSpans() {}

    public static List<AbstractTracePlotPane.RfSpan> compute(
        CompiledSimulation simulation, List<PulseSegment> pulse, Color colour, double opacity
    ) {
        var spans = new ArrayList<AbstractTracePlotPane.RfSpan>();
        if (simulation == null || simulation.segments() == null || pulse == null) return spans;
        var segments = simulation.segments();

        double accumulatedTime = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = segments.get(segmentIndex);
            var steps = pulse.get(segmentIndex).steps();
            Double rfStart = null;
            double stepTime = accumulatedTime;
            for (var step : steps) {
                if (step.isRfOn() && rfStart == null) rfStart = stepTime;
                if (!step.isRfOn() && rfStart != null) {
                    spans.add(new AbstractTracePlotPane.RfSpan(rfStart, stepTime, colour, opacity));
                    rfStart = null;
                }
                stepTime += segment.dt() * 1e6;
            }
            if (rfStart != null) {
                spans.add(new AbstractTracePlotPane.RfSpan(rfStart, stepTime, colour, opacity));
            }
            accumulatedTime += segment.totalSteps() * segment.dt() * 1e6;
        }
        return spans;
    }
}
