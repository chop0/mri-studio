package ax.xz.mri.model.simulation;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the unified field model: FieldDefinition, SimulationConfig, and eigenfield integration. */
class FieldModelTest {

    private static final ProjectNodeId EF_B0 = new ProjectNodeId("ef-b0");
    private static final ProjectNodeId EF_GX = new ProjectNodeId("ef-gx");
    private static final ProjectNodeId EF_GZ = new ProjectNodeId("ef-gz");
    private static final ProjectNodeId EF_RF = new ProjectNodeId("ef-rf");

    // --- FieldDefinition ---

    @Test
    void dcFieldIsDCAndRequiresOneControlChannel() {
        var field = new FieldDefinition("Gradient X", ControlType.LINEAR, -0.03, 0.03, 0, EF_GX);
        assertTrue(field.isDC());
        assertEquals(1, field.controlChannelCount());
    }

    @Test
    void rfFieldIsNotDCAndRequiresTwoControlChannels() {
        var field = new FieldDefinition("RF", ControlType.LINEAR, 0, 200e-6, 655000, EF_RF);
        assertFalse(field.isDC());
        assertEquals(2, field.controlChannelCount());
    }

    @Test
    void binaryFieldRequiresZeroControlChannels() {
        var field = new FieldDefinition("B0", ControlType.BINARY, 0, 0.0154, 0, EF_B0);
        assertEquals(0, field.controlChannelCount());
    }

    @Test
    void fieldDefinitionWithMethodsProduceNewInstances() {
        var original = new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, EF_B0);
        var renamed = original.withName("Main Field");
        var retyped = original.withControlType(ControlType.LINEAR);
        var rescaled = original.withMaxAmplitude(1.5);
        var refreqd = original.withBasebandFrequencyHz(42e6);
        var relinked = original.withEigenfieldId(EF_RF);

        assertEquals("Main Field", renamed.name());
        assertEquals(ControlType.LINEAR, retyped.controlType());
        assertEquals(1.5, rescaled.maxAmplitude());
        assertEquals(42e6, refreqd.basebandFrequencyHz());
        assertEquals(EF_RF, relinked.eigenfieldId());
        // Original is unchanged (records are immutable)
        assertEquals("B0", original.name());
        assertEquals(ControlType.BINARY, original.controlType());
    }

    // --- SimulationConfig field extraction ---

    @Test
    void b0TeslaExtractedFromFirstBinaryDCField() {
        var fields = List.of(
            new FieldDefinition("B0", ControlType.BINARY, 0, 0.0154, 0, EF_B0),
            new FieldDefinition("Gradient X", ControlType.LINEAR, -0.03, 0.03, 0, EF_GX)
        );
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, fields, List.of());
        assertEquals(0.0154, config.b0Tesla(), 1e-10);
    }

    @Test
    void b0TeslaReturnsZeroWhenNoBinaryDCField() {
        var fields = List.of(
            new FieldDefinition("Gradient X", ControlType.LINEAR, -0.03, 0.03, 0, EF_GX)
        );
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, fields, List.of());
        assertEquals(0.0, config.b0Tesla());
    }

    @Test
    void b0TeslaIgnoresNonDCBinaryFields() {
        var fields = List.of(
            new FieldDefinition("RF Gate", ControlType.BINARY, 0, 200e-6, 655000, EF_RF)
        );
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, fields, List.of());
        assertEquals(0.0, config.b0Tesla());
    }

    @Test
    void configWithFieldsProducesImmutableCopy() {
        var fields = new java.util.ArrayList<>(List.of(
            new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, EF_B0)
        ));
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, fields, List.of());
        fields.add(new FieldDefinition("Extra", ControlType.LINEAR, 0, 1, 0, EF_GX));
        assertEquals(1, config.fields().size(), "Config fields should be an immutable copy");
    }

    @Test
    void configWithFieldsMethodReplacesFieldList() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, List.of(), List.of());
        var updated = config.withFields(List.of(
            new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, EF_B0)
        ));
        assertEquals(0, config.fields().size());
        assertEquals(1, updated.fields().size());
    }

    // --- EigenfieldDocument ---

    @Test
    void eigenfieldDocumentHasCorrectKind() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", EigenfieldPreset.UNIFORM_BZ);
        assertEquals(ax.xz.mri.project.ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void eigenfieldWithMethodsProduceNewInstances() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", EigenfieldPreset.UNIFORM_BZ);
        var renamed = ef.withName("Main Field");
        var redescd = ef.withDescription("new desc");
        var represetd = ef.withPreset(EigenfieldPreset.BIOT_SAVART_HELMHOLTZ);

        assertEquals("Main Field", renamed.name());
        assertEquals("new desc", redescd.description());
        assertEquals(EigenfieldPreset.BIOT_SAVART_HELMHOLTZ, represetd.preset());
        assertEquals("B0", ef.name()); // original unchanged
    }

    // --- EigenfieldPreset ---

    @Test
    void allPresetsHaveDisplayNameAndDescription() {
        for (var preset : EigenfieldPreset.values()) {
            assertNotNull(preset.displayName());
            assertFalse(preset.displayName().isBlank());
            assertNotNull(preset.description());
            assertFalse(preset.description().isBlank());
        }
    }
}
