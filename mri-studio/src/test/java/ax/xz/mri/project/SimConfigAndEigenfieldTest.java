package ax.xz.mri.project;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for eigenfield/sim-config lifecycle: CRUD, promotion, and BlochData generation. */
class SimConfigAndEigenfieldTest {

    private static final String UNIFORM_BZ_SRC = "return Vec3.of(0, 0, 1);";

    /** Helper: create a sim config using the standard low-field MRI template. */
    private static SimulationConfigDocument createConfig(ProjectSessionViewModel session, String name, double b0Tesla) {
        var params = ObjectFactory.PhysicsParams.DEFAULTS;
        return session.createSimConfig(name, SimConfigTemplate.LOW_FIELD_MRI, params);
    }

    private static java.util.List<SimulationConfigDocument> configForSequence(ProjectRepository repo, ProjectNodeId seqId) {
        var seq = (SequenceDocument) repo.node(seqId);
        if (seq == null || seq.activeSimConfigId() == null) return java.util.List.of();
        var config = repo.simConfig(seq.activeSimConfigId());
        return config != null ? java.util.List.of(config) : java.util.List.of();
    }

    // --- Eigenfield CRUD ---

    @Test
    void addAndRetrieveEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "Main field", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);

        assertTrue(repo.eigenfieldIds().contains(ef.id()));
        assertEquals(ef, repo.node(ef.id()));
    }

    @Test
    void removeEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "Main field", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);
        repo.removeEigenfield(ef.id());

        assertFalse(repo.eigenfieldIds().contains(ef.id()));
        assertNull(repo.node(ef.id()));
    }

    @Test
    void renameEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "desc", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);
        var renamed = repo.renameEigenfield(ef.id(), "Main Magnet");

        assertEquals("Main Magnet", renamed.name());
        assertEquals("Main Magnet", ((EigenfieldDocument) repo.node(ef.id())).name());
    }

    @Test
    void updateEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "desc", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);

        var updated = ef.withScript("return Vec3.of(0, 0, 1 + 0.01 * z);");
        repo.updateEigenfield(updated);

        assertEquals("return Vec3.of(0, 0, 1 + 0.01 * z);",
            ((EigenfieldDocument) repo.node(ef.id())).script());
    }

    @Test
    void addEigenfieldIsIdempotent() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "desc", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);
        repo.addEigenfield(ef);

        assertEquals(1, repo.eigenfieldIds().size());
    }

    // --- SimConfig CRUD ---

    @Test
    void renameSimConfig() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "d", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 3.0, 1e-6,
            List.of(new FieldDefinition("B0", ef.id(), AmplitudeKind.STATIC, 0, 0, 3.0)));
        var doc = new SimulationConfigDocument(new ProjectNodeId("sc-1"), "Old Name", config);
        repo.addSimConfig(doc);

        var renamed = repo.renameSimConfig(doc.id(), "New Name");

        assertEquals("New Name", renamed.name());
        assertEquals("New Name", repo.node(doc.id()).name());
    }

    @Test
    void simConfigLookupById() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "d", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 3.0, 1e-6,
            List.of(new FieldDefinition("B0", ef.id(), AmplitudeKind.STATIC, 0, 0, 3.0)));
        var doc = new SimulationConfigDocument(new ProjectNodeId("sc-1"), "Test", config);
        repo.addSimConfig(doc);

        assertNotNull(repo.simConfig(doc.id()));
        assertEquals("Test", repo.simConfig(doc.id()).name());
        assertNull(repo.simConfig(new ProjectNodeId("nonexistent")));
    }

    // --- Promotion ---

    @Test
    void promotingSnapshotCreatesSimConfigWithEigenfields() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        var alphaScenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> !node.iterative())
            .findFirst()
            .orElseThrow();
        session.openNode(alphaScenario.id());

        session.promoteActiveSnapshotToSequence();

        var repo = session.repository.get();
        assertEquals(1, repo.sequenceIds().size());
        var seqId = repo.sequenceIds().getFirst();
        var seqDoc = (SequenceDocument) repo.node(seqId);
        assertNotNull(seqDoc.activeSimConfigId());
        var simConfig = repo.simConfig(seqDoc.activeSimConfigId());
        assertNotNull(simConfig);
        assertNotNull(simConfig.config());
        assertEquals(4, simConfig.config().fields().size());

        for (var field : simConfig.config().fields()) {
            assertNotNull(field.eigenfieldId());
            var eigenNode = repo.node(field.eigenfieldId());
            assertInstanceOf(EigenfieldDocument.class, eigenNode);
        }

        assertTrue(repo.eigenfieldIds().size() >= 4);
    }

    @Test
    void createDefaultSimConfigCreatesEigenfieldsAndConfig() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session, "Test Config", 0.0154);
        var repo = session.repository.get();

        assertNotNull(doc);
        assertEquals("Test Config", doc.name());
        assertNotNull(doc.config());
        assertTrue(repo.simConfigIds().contains(doc.id()));
        assertEquals(4, doc.config().fields().size());
        assertEquals(0.0154, doc.config().referenceB0Tesla(), 1e-10);

        var rfField = doc.config().fields().stream()
            .filter(f -> f.name().equals("RF"))
            .findFirst().orElseThrow();
        assertTrue(rfField.carrierHz() > 0, "RF should have non-zero carrier");
        assertEquals(AmplitudeKind.QUADRATURE, rfField.kind());

        for (var field : doc.config().fields()) {
            assertInstanceOf(EigenfieldDocument.class, repo.node(field.eigenfieldId()));
        }
    }

    @Test
    void createMultipleConfigsReusesEigenfields() {
        var session = new ProjectSessionViewModel();
        createConfig(session, "Config 1", 0.0154);
        int first = session.repository.get().eigenfieldIds().size();
        createConfig(session, "Config 2", 3.0);
        int second = session.repository.get().eigenfieldIds().size();
        assertEquals(first, second, "Second config should reuse existing eigenfields, not duplicate them");
    }

    // --- BlochDataFactory ---

    @Test
    void blochDataFactoryProducesValidDataFromFieldConfig() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-b0"), "B0", "d", UNIFORM_BZ_SRC);
        repo.addEigenfield(ef);

        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 1e-6,
            List.of(new FieldDefinition("B0", ef.id(), AmplitudeKind.STATIC, 0, 0, 1.5)));

        var segments = List.of(new Segment(1e-6, 0, 2), new Segment(1e-6, 2, 0));
        var data = BlochDataFactory.build(config, segments, repo);

        assertNotNull(data);
        assertNotNull(data.field());
        assertEquals(1.5, data.field().b0Ref, 1e-10);
        assertEquals(267.522e6, data.field().gamma, 1e3);
        assertEquals(1.0, data.field().t1, 1e-10);
        assertEquals(0.1, data.field().t2, 1e-10);
        assertNotNull(data.field().staticBz);
        assertNotNull(data.field().mz0);
        // Probe points no longer come from config — they're runtime state.
        assertEquals(0, data.iso().size());

        // Uniform Bz at amplitude 1.5 T means local Bz matches the reference everywhere
        // → staticBz (off-resonance) is zero.
        for (double[] row : data.field().staticBz) {
            for (double v : row) assertEquals(0.0, v, 1e-9);
        }
    }

    // --- Delete / rename ---

    @Test
    void deletingSequenceDoesNotOrphanSimConfig() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        var alphaScenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> !node.iterative())
            .findFirst()
            .orElseThrow();
        session.openNode(alphaScenario.id());
        session.promoteActiveSnapshotToSequence();

        var repo = session.repository.get();
        var seqId = repo.sequenceIds().getFirst();
        assertTrue(configForSequence(repo, seqId).size() > 0);

        session.deleteSequence(seqId);
        assertTrue(repo.sequenceIds().isEmpty());
        assertFalse(repo.simConfigIds().isEmpty());
    }

    @Test
    void deleteSimConfigRemovesFromRepository() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session, "Test", 3.0);
        var repo = session.repository.get();
        assertTrue(repo.simConfigIds().contains(doc.id()));
        session.deleteSimConfig(doc.id());
        assertFalse(repo.simConfigIds().contains(doc.id()));
    }

    @Test
    void deleteEigenfieldRemovesFromRepository() {
        var session = new ProjectSessionViewModel();
        createConfig(session, "Test", 3.0);
        var repo = session.repository.get();
        var efId = repo.eigenfieldIds().getFirst();
        assertTrue(repo.eigenfieldIds().contains(efId));
        session.deleteEigenfield(efId);
        assertFalse(repo.eigenfieldIds().contains(efId));
    }

    @Test
    void renameSimConfigUpdatesNameInRepository() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session, "Old Name", 3.0);
        session.renameSimConfig(doc.id(), "New Name");
        assertEquals("New Name", session.repository.get().node(doc.id()).name());
    }

    @Test
    void renameEigenfieldUpdatesNameInRepository() {
        var session = new ProjectSessionViewModel();
        createConfig(session, "Test", 3.0);
        var repo = session.repository.get();
        var efId = repo.eigenfieldIds().getFirst();
        session.renameEigenfield(efId, "Renamed EF");
        assertEquals("Renamed EF", repo.node(efId).name());
    }
}
