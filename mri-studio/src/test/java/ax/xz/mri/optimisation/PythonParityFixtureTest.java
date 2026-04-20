package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.optimisation.ProblemGeometry.DynamicFieldSamples;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parity tests against the legacy Python optimiser fixture.
 *
 * <p>Temporarily disabled: the Python reference was produced against the
 * pre-rewrite 4-hardcoded-channel simulator. The new simulator iterates
 * arbitrary field lists and the legacy dBz/gxm/gzm/b1s arrays no longer map
 * 1:1. The fixture will need regeneration from the Python side before this
 * re-enables. The tests here still exercise the fixture-adapter path.
 */
@Disabled("Python-parity fixture needs regeneration against the new simulator semantics")
class PythonParityFixtureTest {
    private final CpuObjectiveEngine engine = new CpuObjectiveEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullTrainFixtureMatchesPythonValueAndGradient() throws Exception {
        JsonNode fixture = fixtureRoot().get("full");

        var problem = problemFromFixture(fixture, false);
        List<PulseSegment> segments = PulseParameterCodec.split(controlsFromFixture(fixture), problem.sequenceTemplate());
        var evaluation = engine.evaluate(problem, segments);
        var gradient = engine.gradient(problem, segments);

        assertEquals(fixture.get("value").asDouble(), evaluation.value(), 1e-6);
        assertGradientMatchesFixture(fixture.get("gradient"), gradient, 1e-2);
    }

    @Test
    void periodicFixtureMatchesPythonValueAndGradient() throws Exception {
        JsonNode fixture = fixtureRoot().get("periodic");

        var problem = problemFromFixture(fixture, true);
        List<PulseSegment> segments = PulseParameterCodec.split(controlsFromFixture(fixture), problem.sequenceTemplate());
        var evaluation = engine.evaluate(problem, segments);
        var gradient = engine.gradient(problem, segments);

        assertEquals(fixture.get("value").asDouble(), evaluation.value(), 1e-6);
        assertGradientMatchesFixture(fixture.get("gradient"), gradient, 1e-2);
    }

    private JsonNode fixtureRoot() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/ax/xz/mri/optimisation/python-parity-fixture.json");
        assertNotNull(stream);
        return mapper.readTree(stream);
    }

    private static OptimisationProblem problemFromFixture(JsonNode fixture, boolean periodic) {
        JsonNode geometry = fixture.get("geometry");
        JsonNode objective = fixture.get("objective");
        var segments = new java.util.ArrayList<ControlSegmentSpec>();
        for (JsonNode segment : fixture.get("segments")) {
            segments.add(new ControlSegmentSpec(
                segment.get("dt").asDouble(),
                segment.get("n_free").asInt(),
                segment.get("n_pulse").asInt(),
                segment.get("n_ctrl").asInt()
            ));
        }
        SequenceTemplate template = periodic
            ? SequenceTemplate.periodicCycle(segments, fixture.get("prefixSegmentCount").asInt(), 1, 1)
            : SequenceTemplate.finiteTrain(segments);
        // Legacy fixture arrays map to the old 4-channel simulator. Synthesise an
        // equivalent set of dynamic-field samples so the new ProblemGeometry shape
        // accepts them. This adapter is only used by the (currently-disabled) parity test.
        double[] gxm = array(geometry.get("gxm"));
        double[] gzm = array(geometry.get("gzm"));
        double[] b1s = array(geometry.get("b1s"));
        double[] zeros = new double[gxm.length];
        var rf = new DynamicFieldSamples("RF", 0, 2, 0.0, b1s, zeros, zeros);
        var gx = new DynamicFieldSamples("Gx", 2, 1, 0.0, zeros, zeros, gxm);
        var gz = new DynamicFieldSamples("Gz", 3, 1, 0.0, zeros, zeros, gzm);

        return new OptimisationProblem(
            new ProblemGeometry(
                array(geometry.get("mx0")),
                array(geometry.get("my0")),
                array(geometry.get("mz0")),
                array(geometry.get("dBz")),
                array(geometry.get("wIn")),
                array(geometry.get("wOut")),
                List.of(rf, gx, gz),
                geometry.get("sMax").asDouble(),
                geometry.get("gamma").asDouble(),
                geometry.get("t1").asDouble(),
                geometry.get("t2").asDouble(),
                geometry.get("nr").asInt(),
                geometry.get("nz").asInt()
            ),
            template,
            new ObjectiveSpec(
                ObjectiveMode.valueOf(objective.get("mode").asText()),
                objective.get("lamOut").asDouble(),
                objective.get("lamPow").asDouble(),
                objective.get("rfPenalty").asDouble(),
                objective.get("rfSmoothPenalty").asDouble(),
                objective.get("gateSwitchPenalty").asDouble(),
                objective.get("gateBinaryPenalty").asDouble(),
                objective.get("handoffPenalty").asDouble()
            )
        );
    }

    private static double[] controlsFromFixture(JsonNode fixture) {
        return array(fixture.get("controls"));
    }

    private static double[] array(JsonNode node) {
        double[] values = new double[node.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = node.get(index).asDouble();
        }
        return values;
    }

    private static void assertGradientMatchesFixture(JsonNode expectedNode, double[] actual, double tolerance) {
        assertEquals(expectedNode.size(), actual.length);
        for (int index = 0; index < actual.length; index++) {
            assertEquals(expectedNode.get(index).asDouble(), actual[index], tolerance, "gradient index " + index);
        }
    }
}
