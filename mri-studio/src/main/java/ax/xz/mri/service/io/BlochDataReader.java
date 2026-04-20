package ax.xz.mri.service.io;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.ImportedFieldMap;
import ax.xz.mri.model.field.ImportedFieldMapAdapter;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.scenario.BlochData.IsochromatDef;
import ax.xz.mri.model.scenario.Scenario;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads bloch_data.json, translating the legacy 4-channel wire format into the
 * general multi-field runtime representation.
 *
 * <p>The file's {@code "field"} object is read as an {@link ImportedFieldMap}
 * and adapted via {@link ImportedFieldMapAdapter}. PulseSteps are read as
 * 5-element arrays {@code [b1x, b1y, gx, gz, rfGate]} and lifted into
 * {@code PulseStep(controls = [b1x, b1y, gx, gz], rfGate)} — matching the
 * channel layout the adapter chooses.
 */
public final class BlochDataReader {
    private BlochDataReader() {}

    public static BlochData read(File file) throws IOException {
        var mapper = new ObjectMapper();
        var module = new SimpleModule();
        module.addDeserializer(PulseStep.class,      new PulseStepDeserializer());
        module.addDeserializer(PulseSegment.class,   new PulseSegmentDeserializer());
        module.addDeserializer(IsochromatDef.class,  new IsochromatDefDeserializer());
        module.addDeserializer(Scenario.class,       new ScenarioDeserializer(mapper));
        mapper.registerModule(module);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var legacy = mapper.readValue(file, LegacyBlochData.class);
        FieldMap field = legacy.field() == null ? null : ImportedFieldMapAdapter.adapt(legacy.field());
        return new BlochData(field, legacy.iso(), legacy.scenarios());
    }

    /** Intermediate record used only in this reader — JSON shape mirrors the legacy on-disk format. */
    private record LegacyBlochData(
        @JsonProperty("field")     ImportedFieldMap         field,
        @JsonProperty("iso")       List<IsochromatDef>      iso,
        @JsonProperty("scenarios") Map<String, Scenario>    scenarios
    ) {
        @JsonCreator
        public LegacyBlochData { }
    }

    // ── PulseStep: legacy [b1x, b1y, gx, gz, rfGate] → controls+rfGate ──────

    private static final class PulseStepDeserializer extends StdDeserializer<PulseStep> {
        PulseStepDeserializer() { super(PulseStep.class); }

        @Override
        public PulseStep deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            double[] controls = new double[4];
            p.nextToken(); controls[0] = p.getDoubleValue();
            p.nextToken(); controls[1] = p.getDoubleValue();
            p.nextToken(); controls[2] = p.getDoubleValue();
            p.nextToken(); controls[3] = p.getDoubleValue();
            p.nextToken(); double gate = p.getDoubleValue();
            p.nextToken(); // END_ARRAY
            return new PulseStep(controls, gate);
        }
    }

    private static final class PulseSegmentDeserializer extends StdDeserializer<PulseSegment> {
        PulseSegmentDeserializer() { super(PulseSegment.class); }

        @Override
        public PulseSegment deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            var steps = new ArrayList<PulseStep>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                steps.add(new PulseStepDeserializer().deserialize(p, ctx));
            }
            return new PulseSegment(steps);
        }
    }

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

    private static final class ScenarioDeserializer extends StdDeserializer<Scenario> {
        private final ObjectMapper mapper;

        ScenarioDeserializer(ObjectMapper mapper) {
            super(Scenario.class);
            this.mapper = mapper;
        }

        @Override
        public Scenario deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            Map<String, List<PulseSegment>> pulses = new HashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = p.currentName();
                p.nextToken();
                if ("pulses".equals(fieldName)) {
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
                    p.skipChildren();
                }
            }
            return new Scenario(pulses);
        }
    }
}
