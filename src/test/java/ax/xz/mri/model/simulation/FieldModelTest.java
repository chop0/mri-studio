package ax.xz.mri.model.simulation;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the physics-side model: {@link AmplitudeKind}, {@link SimulationConfig}, {@link EigenfieldDocument}. */
class FieldModelTest {

    private static final ProjectNodeId CIRCUIT = new ProjectNodeId("circuit-0");
    private static final ProjectNodeId EF_B0 = new ProjectNodeId("ef-b0");

    private static final double DT = 1e-6;

    private static SimulationConfig cfg(ProjectNodeId circuitId) {
        return new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT, circuitId);
    }

    @Test
    void amplitudeKindChannelCounts() {
        // Every kind is a single scalar now — quadrature drives are composed
        // from two REAL sources fed through a Modulator block.
        assertEquals(0, AmplitudeKind.STATIC.channelCount());
        assertEquals(1, AmplitudeKind.REAL.channelCount());
        assertEquals(1, AmplitudeKind.GATE.channelCount());
    }

    @Test
    void omegaSimIsGammaTimesReferenceB0() {
        var config = cfg(CIRCUIT);
        assertEquals(267.522e6 * 1.5, config.omegaSim(), 1e-3);
    }

    @Test
    void withReferenceB0TeslaReturnsUpdatedCopy() {
        var config = cfg(CIRCUIT);
        assertEquals(3.0, config.withReferenceB0Tesla(3.0).referenceB0Tesla());
        assertEquals(1.5, config.referenceB0Tesla());
    }

    @Test
    void eigenfieldDocumentKindIsEigenfield() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);", "T");
        assertEquals(ax.xz.mri.project.ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void eigenfieldWithMethodsProduceNewInstances() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);", "T");
        assertEquals("Main", ef.withName("Main").name());
        assertEquals("new", ef.withDescription("new").description());
        assertEquals("return Vec3.ZERO;", ef.withScript("return Vec3.ZERO;").script());
        assertEquals("B0", ef.name());
    }

    @Test
    void zeroOrNegativeDtRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 0.0, CIRCUIT));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, -1e-6, CIRCUIT));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, Double.NaN, CIRCUIT));
    }

    @Test
    void withDtSecondsCopies() {
        var config = cfg(CIRCUIT);
        var updated = config.withDtSeconds(5e-7);
        assertEquals(5e-7, updated.dtSeconds(), 0);
        assertEquals(DT, config.dtSeconds(), 0);
        assertEquals(1000, updated.t1Ms(), 0);
        assertEquals(1.5, updated.referenceB0Tesla(), 0);
    }

    @Test
    void larmorAndNyquistDerivations() {
        var config = cfg(CIRCUIT);
        assertEquals(267.522e6 * 1.5 / (2 * Math.PI), config.larmorHz(), 1e-3);
        assertEquals(500_000, config.nyquistHz(), 1e-6);
    }

    @Test
    void jsonRoundtripPreservesDt() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = cfg(CIRCUIT).withDtSeconds(2.5e-6);
        String json = mapper.writeValueAsString(original);
        var parsed = mapper.readValue(json, SimulationConfig.class);
        assertEquals(2.5e-6, parsed.dtSeconds(), 0);
        assertEquals(1.5, parsed.referenceB0Tesla(), 0);
        assertEquals(CIRCUIT.value(), parsed.circuitId().value());
    }

    @Test
    void withCircuitIdReturnsUpdatedCopy() {
        var config = cfg(CIRCUIT);
        var other = new ProjectNodeId("circuit-1");
        var updated = config.withCircuitId(other);
        assertEquals(other, updated.circuitId());
        assertEquals(CIRCUIT, config.circuitId());
    }
}
