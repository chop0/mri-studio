package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SignalTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared timing analysis for pulse windows, receive windows, and averaged signal measurements. */
public final class PulseTimelineAnalysis {
    private static final Analysis EMPTY = new Analysis(List.of(), List.of(), List.of(), List.of());

    private PulseTimelineAnalysis() {
    }

    public static Analysis compute(BlochData data, List<PulseSegment> pulse, SignalTrace signalTrace) {
        if (data == null || data.field() == null || data.field().segments == null || pulse == null) {
            return EMPTY;
        }

        var segmentWindows = new ArrayList<TimeWindow>();
        var rfWindows = new ArrayList<TimeWindow>();
        var freeWindows = new ArrayList<TimeWindow>();

        double tMicros = 0.0;
        Boolean windowIsRf = null;
        double windowStart = 0.0;

        for (int segmentIndex = 0; segmentIndex < data.field().segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = data.field().segments.get(segmentIndex);
            var steps = pulse.get(segmentIndex).steps();
            double segmentStart = tMicros;

            for (var step : steps) {
                boolean rfOn = step.isRfOn();
                if (windowIsRf == null) {
                    windowIsRf = rfOn;
                    windowStart = tMicros;
                } else if (windowIsRf != rfOn) {
                    addWindow(windowIsRf, windowStart, tMicros, rfWindows, freeWindows);
                    windowIsRf = rfOn;
                    windowStart = tMicros;
                }
                tMicros += segment.dt() * 1e6;
            }

            segmentWindows.add(new TimeWindow(segmentStart, tMicros));
        }

        if (windowIsRf != null) {
            addWindow(windowIsRf, windowStart, tMicros, rfWindows, freeWindows);
        }

        var measurements = new ArrayList<MeasurementWindow>();
        double maxAverage = 0.0;
        int ordinal = 1;
        for (var freeWindow : freeWindows) {
            double average = averageSignal(signalTrace, freeWindow.startMicros(), freeWindow.endMicros());
            measurements.add(new MeasurementWindow(
                ordinal++,
                freeWindow.startMicros(),
                freeWindow.endMicros(),
                freeWindow.centerMicros(),
                average,
                0.0
            ));
            maxAverage = Math.max(maxAverage, average);
        }

        if (maxAverage > 1e-9) {
            for (int index = 0; index < measurements.size(); index++) {
                var measurement = measurements.get(index);
                measurements.set(index, measurement.withNormalizedAverage(measurement.averageSignal() / maxAverage));
            }
        }

        return new Analysis(
            List.copyOf(segmentWindows),
            List.copyOf(rfWindows),
            List.copyOf(freeWindows),
            List.copyOf(measurements)
        );
    }

    private static void addWindow(
        boolean rf,
        double startMicros,
        double endMicros,
        List<TimeWindow> rfWindows,
        List<TimeWindow> freeWindows
    ) {
        if (endMicros <= startMicros) return;
        var window = new TimeWindow(startMicros, endMicros);
        (rf ? rfWindows : freeWindows).add(window);
    }

    private static double averageSignal(SignalTrace signalTrace, double startMicros, double endMicros) {
        if (signalTrace == null || signalTrace.points().isEmpty() || endMicros <= startMicros) {
            return 0.0;
        }

        var points = signalTrace.points();
        double clampedStart = Math.max(startMicros, points.get(0).tMicros());
        double clampedEnd = Math.min(endMicros, points.get(points.size() - 1).tMicros());
        if (clampedEnd <= clampedStart) return 0.0;

        double area = 0.0;
        double previousTime = clampedStart;
        double previousValue = signalAt(points, clampedStart);

        for (var point : points) {
            if (point.tMicros() <= clampedStart) continue;
            if (point.tMicros() >= clampedEnd) break;
            area += trapezoid(previousTime, point.tMicros(), previousValue, point.signal());
            previousTime = point.tMicros();
            previousValue = point.signal();
        }

        double endValue = signalAt(points, clampedEnd);
        area += trapezoid(previousTime, clampedEnd, previousValue, endValue);
        return area / (clampedEnd - clampedStart);
    }

    private static double signalAt(List<SignalTrace.Point> points, double timeMicros) {
        if (points.isEmpty()) return 0.0;
        if (timeMicros <= points.get(0).tMicros()) return points.get(0).signal();
        if (timeMicros >= points.get(points.size() - 1).tMicros()) return points.get(points.size() - 1).signal();

        for (int index = 1; index < points.size(); index++) {
            var right = points.get(index);
            if (timeMicros > right.tMicros()) continue;
            var left = points.get(index - 1);
            double span = right.tMicros() - left.tMicros();
            double fraction = span <= 1e-9 ? 0.0 : (timeMicros - left.tMicros()) / span;
            return left.signal() + fraction * (right.signal() - left.signal());
        }

        return points.get(points.size() - 1).signal();
    }

    private static double trapezoid(double t0, double t1, double v0, double v1) {
        return Math.max(0.0, t1 - t0) * (v0 + v1) * 0.5;
    }

    public record TimeWindow(double startMicros, double endMicros) implements Comparable<TimeWindow> {
        public double durationMicros() {
            return Math.max(0.0, endMicros - startMicros);
        }

        public double centerMicros() {
            return startMicros + durationMicros() * 0.5;
        }

        @Override
        public int compareTo(TimeWindow other) {
            return Comparator.comparingDouble(TimeWindow::startMicros)
                .thenComparingDouble(TimeWindow::endMicros)
                .compare(this, other);
        }
    }

    public record MeasurementWindow(
        int ordinal,
        double startMicros,
        double endMicros,
        double centerMicros,
        double averageSignal,
        double normalizedAverage
    ) {
        public MeasurementWindow withNormalizedAverage(double value) {
            return new MeasurementWindow(ordinal, startMicros, endMicros, centerMicros, averageSignal, value);
        }
    }

    public record Analysis(
        List<TimeWindow> segmentWindows,
        List<TimeWindow> rfWindows,
        List<TimeWindow> freeWindows,
        List<MeasurementWindow> measurements
    ) {
        public boolean isEmpty() {
            return segmentWindows.isEmpty() && rfWindows.isEmpty() && freeWindows.isEmpty() && measurements.isEmpty();
        }
    }
}
