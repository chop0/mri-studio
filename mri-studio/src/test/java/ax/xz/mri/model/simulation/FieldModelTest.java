package ax.xz.mri.model.simulation;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the field model: {@link FieldDefinition}, {@link SimulationConfig}, {@link EigenfieldDocument}. */
class FieldModelTest {

    private static final ProjectNodeId EF_B0 = new ProjectNodeId("ef-b0");
    private static final ProjectNodeId EF_GX = new ProjectNodeId("ef-gx");
    private static final ProjectNodeId EF_RF = new ProjectNodeId("ef-rf");

    private static final double DT = 1e-6;

    // --- FieldDefinition / AmplitudeKind ---

    @Test
    void amplitudeKindChannelCounts() {
        assertEquals(0, AmplitudeKind.STATIC.channelCount());
        assertEquals(1, AmplitudeKind.REAL.channelCount());
        assertEquals(2, AmplitudeKind.QUADRATURE.channelCount());
    }

    @Test
    void fieldDefinitionWithMethodsProduceNewInstances() {
        var original = new FieldDefinition("B0", EF_B0, AmplitudeKind.STATIC, 0, 0, 3.0);
        assertEquals("Main", original.withName("Main").name());
        assertEquals(AmplitudeKind.REAL, original.withKind(AmplitudeKind.REAL).kind());
        assertEquals(1.5, original.withMaxAmplitude(1.5).maxAmplitude());
        assertEquals(42e6, original.withCarrierHz(42e6).carrierHz());
        assertEquals(EF_RF, original.withEigenfieldId(EF_RF).eigenfieldId());
        assertEquals("B0", original.name());
        assertEquals(AmplitudeKind.STATIC, original.kind());
    }

    @Test
    void fieldChannelCountFollowsAmplitudeKind() {
        assertEquals(0, new FieldDefinition("B0", EF_B0, AmplitudeKind.STATIC, 0, 0, 3.0).channelCount());
        assertEquals(1, new FieldDefinition("Gx", EF_GX, AmplitudeKind.REAL, 0, -1, 1).channelCount());
        assertEquals(2, new FieldDefinition("RF", EF_RF, AmplitudeKind.QUADRATURE, 1e6, 0, 200e-6).channelCount());
    }

    // --- SimulationConfig ---

    @Test
    void totalChannelCountSumsOverFields() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT,
            List.of(
                new FieldDefinition("B0", EF_B0, AmplitudeKind.STATIC, 0, 0, 1.5),
                new FieldDefinition("Gx", EF_GX, AmplitudeKind.REAL, 0, -1, 1),
                new FieldDefinition("RF", EF_RF, AmplitudeKind.QUADRATURE, 1e6, 0, 200e-6)
            ));
        assertEquals(3, config.totalChannelCount());
    }

    @Test
    void omegaSimIsGammaTimesReferenceB0() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT, List.of());
        assertEquals(267.522e6 * 1.5, config.omegaSim(), 1e-3);
    }

    @Test
    void withReferenceB0TeslaReturnsUpdatedCopy() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT, List.of());
        assertEquals(3.0, config.withReferenceB0Tesla(3.0).referenceB0Tesla());
        assertEquals(1.5, config.referenceB0Tesla());
    }

    @Test
    void withFieldsProducesImmutableCopy() {
        var fields = new java.util.ArrayList<>(List.of(
            new FieldDefinition("B0", EF_B0, AmplitudeKind.STATIC, 0, 0, 3.0)));
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 3.0, DT, fields);
        fields.add(new FieldDefinition("Extra", EF_GX, AmplitudeKind.REAL, 0, -1, 1));
        assertEquals(1, config.fields().size());
    }

    // --- EigenfieldDocument ---

    @Test
    void eigenfieldDocumentKindIsEigenfield() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);");
        assertEquals(ax.xz.mri.project.ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void eigenfieldWithMethodsProduceNewInstances() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);");
        assertEquals("Main", ef.withName("Main").name());
        assertEquals("new", ef.withDescription("new").description());
        assertEquals("return Vec3.ZERO;", ef.withScript("return Vec3.ZERO;").script());
        assertEquals("B0", ef.name());
    }

    // --- dt validation ---

    @Test
    void zeroOrNegativeDtRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 0.0, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, -1e-6, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, Double.NaN, List.of()));
    }

    @Test
    void withDtSecondsCopies() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT, List.of());
        var updated = config.withDtSeconds(5e-7);
        assertEquals(5e-7, updated.dtSeconds(), 0);
        assertEquals(DT, config.dtSeconds(), 0);
        assertEquals(1000, updated.t1Ms(), 0);
        assertEquals(1.5, updated.referenceB0Tesla(), 0);
    }

    @Test
    void larmorAndNyquistDerivations() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 1e-6, List.of());
        assertEquals(267.522e6 * 1.5 / (2 * Math.PI), config.larmorHz(), 1e-3);
        assertEquals(500_000, config.nyquistHz(), 1e-6);
    }

    @Test
    void jsonRoundtripPreservesDt() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 2.5e-6, List.of());
        String json = mapper.writeValueAsString(original);
        var parsed = mapper.readValue(json, SimulationConfig.class);
        assertEquals(2.5e-6, parsed.dtSeconds(), 0);
        assertEquals(1.5, parsed.referenceB0Tesla(), 0);
    }
}
