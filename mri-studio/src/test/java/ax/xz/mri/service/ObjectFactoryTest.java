package ax.xz.mri.service;

import ax.xz.mri.model.simulation.ControlType;
import ax.xz.mri.model.simulation.EigenfieldPreset;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectFactoryTest {

    // --- PhysicsParams extraction ---

    @Test
    void extractFromFieldMapReturnsCorrectParams() {
        var field = TestBlochDataFactory.sampleDocument().field();
        var params = ObjectFactory.extractFromFieldMap(field);

        assertEquals(1.5, params.b0Tesla(), 1e-10);
        assertEquals(267.5e6, params.gamma(), 1e3);
        assertEquals(1000, params.t1Ms(), 1); // 1.0s → 1000ms
        assertEquals(80, params.t2Ms(), 1);   // 0.08s → 80ms
    }

    @Test
    void extractFromNullFieldMapReturnsDefaults() {
        var params = ObjectFactory.extractFromFieldMap(null);
        assertEquals(ObjectFactory.PhysicsParams.DEFAULTS, params);
    }

    // --- fieldsFromImport ---

    @Test
    void fieldsFromImportCreates4FieldsWithCorrectStructure() {
        var repo = ProjectRepository.untitled();
        var field = TestBlochDataFactory.sampleDocument().field();
        var fields = ObjectFactory.fieldsFromImport(field, repo);

        assertEquals(4, fields.size());

        // B0: binary DC
        var b0 = fields.get(0);
        assertEquals("B0", b0.name());
        assertEquals(ControlType.BINARY, b0.controlType());
        assertTrue(b0.isDC());
        assertEquals(1.5, b0.maxAmplitude(), 1e-10);

        // Gradient X: linear DC
        var gx = fields.get(1);
        assertEquals("Gradient X", gx.name());
        assertEquals(ControlType.LINEAR, gx.controlType());
        assertTrue(gx.isDC());

        // Gradient Z: linear DC
        var gz = fields.get(2);
        assertEquals("Gradient Z", gz.name());
        assertEquals(ControlType.LINEAR, gz.controlType());
        assertTrue(gz.isDC());

        // RF: linear non-DC
        var rf = fields.get(3);
        assertEquals("RF", rf.name());
        assertEquals(ControlType.LINEAR, rf.controlType());
        assertFalse(rf.isDC());
        assertTrue(rf.basebandFrequencyHz() > 0);
    }

    @Test
    void fieldsFromImportCreatesEigenfieldDocuments() {
        var repo = ProjectRepository.untitled();
        var fields = ObjectFactory.fieldsFromImport(TestBlochDataFactory.sampleDocument().field(), repo);

        // Each field references a valid eigenfield
        for (var f : fields) {
            assertNotNull(f.eigenfieldId());
            assertInstanceOf(EigenfieldDocument.class, repo.node(f.eigenfieldId()));
        }

        // Should have created 4 eigenfields
        assertEquals(4, repo.eigenfieldIds().size());
    }

    @Test
    void fieldsFromImportReusesExistingEigenfields() {
        var repo = ProjectRepository.untitled();
        var field = TestBlochDataFactory.sampleDocument().field();

        ObjectFactory.fieldsFromImport(field, repo);
        assertEquals(4, repo.eigenfieldIds().size());

        // Calling again should NOT create duplicates
        ObjectFactory.fieldsFromImport(field, repo);
        assertEquals(4, repo.eigenfieldIds().size());
    }

    // --- findOrCreateEigenfield ---

    @Test
    void findOrCreateEigenfieldCreatesNewIfNoneExist() {
        var repo = ProjectRepository.untitled();
        var ef = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", EigenfieldPreset.UNIFORM_BZ);

        assertNotNull(ef);
        assertEquals("Test", ef.name());
        assertEquals(EigenfieldPreset.UNIFORM_BZ, ef.preset());
        assertTrue(repo.eigenfieldIds().contains(ef.id()));
    }

    @Test
    void findOrCreateEigenfieldReusesMatchingExisting() {
        var repo = ProjectRepository.untitled();
        var ef1 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", EigenfieldPreset.UNIFORM_BZ);
        var ef2 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", EigenfieldPreset.UNIFORM_BZ);

        assertEquals(ef1.id(), ef2.id());
        assertEquals(1, repo.eigenfieldIds().size());
    }

    @Test
    void findOrCreateEigenfieldDoesNotReuseIfPresetDiffers() {
        var repo = ProjectRepository.untitled();
        var ef1 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", EigenfieldPreset.UNIFORM_BZ);
        var ef2 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", EigenfieldPreset.BIOT_SAVART_HELMHOLTZ);

        assertNotEquals(ef1.id(), ef2.id());
        assertEquals(2, repo.eigenfieldIds().size());
    }

    // --- buildConfig ---

    @Test
    void buildConfigProducesValidSimulationConfig() {
        var params = ObjectFactory.PhysicsParams.DEFAULTS;
        var config = ObjectFactory.buildConfig(params, List.of());

        assertEquals(params.t1Ms(), config.t1Ms());
        assertEquals(params.t2Ms(), config.t2Ms());
        assertEquals(params.gamma(), config.gamma());
        assertEquals(params.fovZMm(), config.fovZMm());
        assertEquals(0, config.fields().size());
        assertEquals(3, config.isochromats().size()); // default isochromats
    }

    // --- SimConfigTemplate ---

    @Test
    void emptyTemplateCreatesNoFields() {
        var repo = ProjectRepository.untitled();
        var fields = SimConfigTemplate.EMPTY.createFields(0.0154, 267.522e6, repo);
        assertTrue(fields.isEmpty());
        assertTrue(repo.eigenfieldIds().isEmpty());
    }

    @Test
    void lowFieldMriTemplateCreates4FieldsWithEigenfields() {
        var repo = ProjectRepository.untitled();
        var fields = SimConfigTemplate.LOW_FIELD_MRI.createFields(0.0154, 267.522e6, repo);

        assertEquals(4, fields.size());
        assertEquals(4, repo.eigenfieldIds().size());

        // All eigenfield references should be valid
        for (var f : fields) {
            assertInstanceOf(EigenfieldDocument.class, repo.node(f.eigenfieldId()));
        }
    }

    @Test
    void templateToStringReturnsDisplayName() {
        assertEquals("Empty", SimConfigTemplate.EMPTY.toString());
        assertEquals("Standard low-field MRI", SimConfigTemplate.LOW_FIELD_MRI.toString());
    }
}
