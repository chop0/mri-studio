package ax.xz.mri.optimisation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.support.TestBlochDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OptimisationTestSupport {
    private OptimisationTestSupport() {
    }

    public static BlochData sampleDocument() {
        return TestBlochDataFactory.sampleDocument();
    }

    public static List<PulseSegment> pulseA() {
        return TestBlochDataFactory.pulseA();
    }

    public static List<PulseSegment> pulseB() {
        return TestBlochDataFactory.pulseB();
    }

    public static SequenceTemplate finiteTemplateFor(List<PulseSegment> pulse) {
        return SequenceTemplate.finiteTrain(pulse.stream()
            .map(segment -> new ControlSegmentSpec(1e-6, segment.steps().size(), 0, 5))
            .toList());
    }

    public static ProblemGeometry singlePointGeometry(double inWeight, double outWeight, double sMax) {
        return new ProblemGeometry(
            new double[]{0.0},
            new double[]{0.0},
            new double[]{1.0},
            new double[]{0.0},
            new double[]{0.0},
            new double[]{0.0},
            new double[]{1.0},
            new double[]{inWeight},
            new double[]{outWeight},
            sMax,
            1.0,
            1.0,
            1.0,
            1,
            1
        );
    }

    public static OptimisationProblem simpleProblem(SequenceTemplate template, ObjectiveSpec objectiveSpec) {
        return new OptimisationProblem(singlePointGeometry(1.0, 0.0, 1.0), template, objectiveSpec);
    }

    public static ObjectiveSpec simpleObjective(ObjectiveMode mode) {
        return new ObjectiveSpec(mode, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public static void writeBlochDataJson(File file, BlochData source) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("field", source.field());
        root.put("iso", source.iso().stream()
            .map(iso -> List.of(iso.name(), iso.colour(), iso.inSlice()))
            .map(values -> (Object) values)
            .toList());
        var scenarios = new LinkedHashMap<String, Object>();
        for (var entry : source.scenarios().entrySet()) {
            var scenario = new LinkedHashMap<String, Object>();
            scenario.put("pulses", encodePulses(entry.getValue().pulses()));
            scenarios.put(entry.getKey(), scenario);
        }
        root.put("scenarios", scenarios);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file, root);
    }

    private static Map<String, Object> encodePulses(Map<String, List<PulseSegment>> pulses) {
        var encoded = new LinkedHashMap<String, Object>();
        for (var entry : pulses.entrySet()) {
            encoded.put(entry.getKey(), encodePulse(entry.getValue()));
        }
        return encoded;
    }

    private static List<Object> encodePulse(List<PulseSegment> segments) {
        return segments.stream()
            .map(segment -> segment.steps().stream()
                .map(step -> List.of(step.b1x(), step.b1y(), step.gx(), step.gz(), step.rfGate()))
                .map(values -> (Object) values)
                .toList())
            .map(values -> (Object) values)
            .toList();
    }
}
