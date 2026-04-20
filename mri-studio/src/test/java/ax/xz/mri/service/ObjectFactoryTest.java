package ax.xz.mri.service;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectFactoryTest {

    private static final String UNIFORM_BZ = "return Vec3.of(0, 0, 1);";
    private static final String GRADIENT_X = "return Vec3.of(0, 0, x);";

    // --- PhysicsParams extraction ---

    @Test
    void extractFromFieldMapReturnsCorrectParams() {
        var field = TestBlochDataFactory.sampleDocument().field();
        var params = ObjectFactory.extractFromFieldMap(field);
        assertEquals(267.5e6, params.gamma(), 1e3);
        assertEquals(1000, params.t1Ms(), 1);
        assertEquals(80, params.t2Ms(), 1);
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

        // Field order: B0 (STATIC), RF (QUADRATURE), Gx (REAL), Gz (REAL).
        // The QUADRATURE-first order keeps PulseStep.controls laid out as
        // [rf_I, rf_Q, gx, gz], matching legacy imports.
        var b0 = fields.get(0);
        assertEquals("B0", b0.name());
        assertEquals(AmplitudeKind.STATIC, b0.kind());
        assertEquals(1.5, b0.maxAmplitude(), 1e-10);

        var rf = fields.get(1);
        assertEquals("RF", rf.name());
        assertEquals(AmplitudeKind.QUADRATURE, rf.kind());
        assertTrue(rf.carrierHz() > 0);

        var gx = fields.get(2);
        assertEquals("Gradient X", gx.name());
        assertEquals(AmplitudeKind.REAL, gx.kind());

        var gz = fields.get(3);
        assertEquals("Gradient Z", gz.name());
        assertEquals(AmplitudeKind.REAL, gz.kind());
    }

    @Test
    void fieldsFromImportCreatesEigenfieldDocuments() {
        var repo = ProjectRepository.untitled();
        var fields = ObjectFactory.fieldsFromImport(TestBlochDataFactory.sampleDocument().field(), repo);
        for (var f : fields) {
            assertNotNull(f.eigenfieldId());
            assertInstanceOf(EigenfieldDocument.class, repo.node(f.eigenfieldId()));
        }
        assertEquals(4, repo.eigenfieldIds().size());
    }

    @Test
    void fieldsFromImportReusesExistingEigenfields() {
        var repo = ProjectRepository.untitled();
        var field = TestBlochDataFactory.sampleDocument().field();
        ObjectFactory.fieldsFromImport(field, repo);
        assertEquals(4, repo.eigenfieldIds().size());
        ObjectFactory.fieldsFromImport(field, repo);
        assertEquals(4, repo.eigenfieldIds().size());
    }

    // --- findOrCreateEigenfield (by script) ---

    @Test
    void findOrCreateEigenfieldCreatesNewIfNoneExist() {
        var repo = ProjectRepository.untitled();
        var ef = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", UNIFORM_BZ);
        assertEquals("Test", ef.name());
        assertEquals(UNIFORM_BZ, ef.script());
        assertTrue(repo.eigenfieldIds().contains(ef.id()));
    }

    @Test
    void findOrCreateEigenfieldReusesMatchingExisting() {
        var repo = ProjectRepository.untitled();
        var ef1 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", UNIFORM_BZ);
        var ef2 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", UNIFORM_BZ);
        assertEquals(ef1.id(), ef2.id());
        assertEquals(1, repo.eigenfieldIds().size());
    }

    @Test
    void findOrCreateEigenfieldDoesNotReuseIfScriptDiffers() {
        var repo = ProjectRepository.untitled();
        var ef1 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", UNIFORM_BZ);
        var ef2 = ObjectFactory.findOrCreateEigenfield(repo, "Test", "desc", GRADIENT_X);
        assertNotEquals(ef1.id(), ef2.id());
        assertEquals(2, repo.eigenfieldIds().size());
    }

    // --- buildConfig ---

    @Test
    void buildConfigProducesValidSimulationConfig() {
        var params = ObjectFactory.PhysicsParams.DEFAULTS;
        var config = ObjectFactory.buildConfig(params, 1.5, List.of());

        assertEquals(params.t1Ms(), config.t1Ms());
        assertEquals(params.gamma(), config.gamma());
        assertEquals(1.5, config.referenceB0Tesla());
        assertEquals(params.dtSeconds(), config.dtSeconds());
        assertEquals(0, config.fields().size());
    }

    // --- SimConfigTemplate ---

    @Test
    void emptyTemplateCreatesNoFields() {
        var repo = ProjectRepository.untitled();
        assertTrue(SimConfigTemplate.EMPTY.createFields(repo).isEmpty());
        assertTrue(repo.eigenfieldIds().isEmpty());
    }

    @Test
    void lowFieldMriTemplateCreates4FieldsWithEigenfields() {
        var repo = ProjectRepository.untitled();
        var fields = SimConfigTemplate.LOW_FIELD_MRI.createFields(repo);
        assertEquals(4, fields.size());
        assertEquals(4, repo.eigenfieldIds().size());
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
