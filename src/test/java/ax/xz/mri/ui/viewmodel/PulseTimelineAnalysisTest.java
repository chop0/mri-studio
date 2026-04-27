package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.support.TestSimulationOutputFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PulseTimelineAnalysisTest {
    @Test
    void computesRfFreeAndMeasurementWindowsFromPulse() {
        var document = TestSimulationOutputFactory.sampleDocument();
        var pulse = TestSimulationOutputFactory.pulseA();
        var signal = new SignalTrace(List.of(
            new SignalTrace.Point(0.0, 0.0, 0.0),
            new SignalTrace.Point(2.0, 1.0, 0.0),
            new SignalTrace.Point(4.0, 1.0, 0.0)
        ));

        var analysis = PulseTimelineAnalysis.compute(document, pulse, signal);

        assertEquals(2, analysis.segmentWindows().size());
        assertEquals(1, analysis.rfWindows().size());
        assertEquals(1, analysis.freeWindows().size());
        assertEquals(1, analysis.measurements().size());

        assertEquals(0.0, analysis.rfWindows().get(0).startMicros(), 1e-9);
        assertEquals(2.0, analysis.rfWindows().get(0).endMicros(), 1e-9);
        assertEquals(2.0, analysis.freeWindows().get(0).startMicros(), 1e-9);
        assertEquals(4.0, analysis.freeWindows().get(0).endMicros(), 1e-9);
        assertEquals(1.0, analysis.measurements().get(0).averageSignal(), 1e-9);
        assertEquals(1.0, analysis.measurements().get(0).normalizedAverage(), 1e-9);
    }
}
