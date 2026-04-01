package ax.xz.mri.optimisation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes optimisation snapshots back out as viewer-compatible JSON. */
public final class SnapshotExporter {
    private final ObjectMapper mapper = new ObjectMapper();

    public void write(File outputFile, BlochData source, OptimisationRequest request, OptimisationResult result) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("field", source.field());
        root.put("iso", encodeIso(source));
        var scenarios = new LinkedHashMap<String, Object>();
        for (var entry : source.scenarios().entrySet()) {
            var scenario = new LinkedHashMap<String, Object>();
            scenario.put("pulses", encodePulses(entry.getValue().pulses()));
            scenarios.put(entry.getKey(), scenario);
        }
        var outputScenario = new LinkedHashMap<String, Object>();
        var pulses = new LinkedHashMap<String, Object>();
        for (var snapshot : result.snapshots().snapshots().entrySet()) {
            pulses.put(String.valueOf(snapshot.getKey()), encodePulse(snapshot.getValue()));
        }
        outputScenario.put("pulses", pulses);
        scenarios.put(request.outputScenarioName(), outputScenario);
        root.put("scenarios", scenarios);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, root);
    }

    private static List<Object> encodeIso(BlochData source) {
        return source.iso().stream()
            .map(iso -> List.of(iso.name(), iso.colour(), iso.inSlice()))
            .map(values -> (Object) values)
            .toList();
    }

    private static Map<String, Object> encodePulses(Map<String, List<PulseSegment>> pulses) {
        var encoded = new LinkedHashMap<String, Object>();
        for (var entry : pulses.entrySet()) {
            encoded.put(entry.getKey(), encodePulse(entry.getValue()));
        }
        return encoded;
    }

    private static List<Object> encodePulse(List<PulseSegment> segments) {
        return segments.stream().map(segment -> segment.steps().stream()
            .map(step -> List.of(step.b1x(), step.b1y(), step.gx(), step.gz(), step.rfGate()))
            .map(values -> (Object) values)
            .toList()).map(values -> (Object) values).toList();
    }
}
