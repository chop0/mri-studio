package ax.xz.mri.service.io;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.scenario.BlochData.IsochromatDef;
import ax.xz.mri.model.scenario.Scenario;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.MapType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads bloch_data.json with custom deserializers for the array-encoded types. */
public final class BlochDataReader {
    private BlochDataReader() {}

    public static BlochData read(File file) throws IOException {
        var mapper = new ObjectMapper();
        var module = new SimpleModule();
        module.addDeserializer(PulseStep.class,       new PulseStepDeserializer());
        module.addDeserializer(PulseSegment.class,    new PulseSegmentDeserializer());
        module.addDeserializer(IsochromatDef.class,   new IsochromatDefDeserializer());
        module.addDeserializer(Scenario.class,        new ScenarioDeserializer(mapper));
        mapper.registerModule(module);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(file, BlochData.class);
    }

    // ── PulseStep: deserialize from [b1x, b1y, gx, gz, rfGate] ──────────────

    private static final class PulseStepDeserializer extends StdDeserializer<PulseStep> {
        PulseStepDeserializer() { super(PulseStep.class); }

        @Override
        public PulseStep deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            p.nextToken(); double b1x = p.getDoubleValue();
            p.nextToken(); double b1y = p.getDoubleValue();
            p.nextToken(); double gx  = p.getDoubleValue();
            p.nextToken(); double gz  = p.getDoubleValue();
            p.nextToken(); double rf  = p.getDoubleValue();
            p.nextToken(); // END_ARRAY
            return new PulseStep(b1x, b1y, gx, gz, rf);
        }
    }

    // ── PulseSegment: array of PulseStep arrays ───────────────────────────────

    private static final class PulseSegmentDeserializer extends StdDeserializer<PulseSegment> {
        PulseSegmentDeserializer() { super(PulseSegment.class); }

        @Override
        public PulseSegment deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            var steps = new ArrayList<PulseStep>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                // current token is START_ARRAY for each PulseStep
                steps.add(new PulseStepDeserializer().deserialize(p, ctx));
            }
            return new PulseSegment(steps);
        }
    }

    // ── IsochromatDef: [name, colour, inSlice] ────────────────────────────────

    private static final class IsochromatDefDeserializer extends StdDeserializer<IsochromatDef> {
        IsochromatDefDeserializer() { super(IsochromatDef.class); }

        @Override
        public IsochromatDef deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            p.nextToken(); String  name    = p.getText();
            p.nextToken(); String  colour  = p.getText();
            p.nextToken(); boolean inSlice = p.getBooleanValue();
            p.nextToken(); // END_ARRAY
            return new IsochromatDef(name, colour, inSlice);
        }
    }

    // ── Scenario: {"pulses": {"key": [[step…], …], …}} ───────────────────────

    private static final class ScenarioDeserializer extends StdDeserializer<Scenario> {
        private final ObjectMapper mapper;

        ScenarioDeserializer(ObjectMapper mapper) {
            super(Scenario.class);
            this.mapper = mapper;
        }

        @Override
        public Scenario deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            // p is at START_OBJECT for the scenario node
            Map<String, List<PulseSegment>> pulses = new HashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = p.currentName();
                p.nextToken();
                if ("pulses".equals(fieldName)) {
                    // object: iterKey → array of PulseSegments
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        String iterKey = p.currentName();
                        p.nextToken(); // START_ARRAY of segments
                        var segs = new ArrayList<PulseSegment>();
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            segs.add(new PulseSegmentDeserializer().deserialize(p, ctx));
                        }
                        pulses.put(iterKey, segs);
                    }
                } else {
                    p.skipChildren(); // ignore "trajectories" etc.
                }
            }
            return new Scenario(pulses);
        }
    }
}
