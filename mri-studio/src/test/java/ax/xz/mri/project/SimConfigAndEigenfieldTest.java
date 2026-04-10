package ax.xz.mri.project;

import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.ControlType;
import ax.xz.mri.model.simulation.EigenfieldPreset;
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

    /** Helper: create a sim config using the standard low-field MRI template. */
    private static SimulationConfigDocument createConfig(ProjectSessionViewModel session, String name, double b0Tesla) {
        var params = ObjectFactory.PhysicsParams.DEFAULTS;
        return session.createSimConfig(name, SimConfigTemplate.LOW_FIELD_MRI, params);
    }

    /** Helper: find the config associated with a sequence (via the sequence's activeSimConfigId). */
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
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "Main field", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);

        assertTrue(repo.eigenfieldIds().contains(ef.id()));
        assertEquals(ef, repo.node(ef.id()));
    }

    @Test
    void removeEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "Main field", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);
        repo.removeEigenfield(ef.id());

        assertFalse(repo.eigenfieldIds().contains(ef.id()));
        assertNull(repo.node(ef.id()));
    }

    @Test
    void renameEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "desc", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);
        var renamed = repo.renameEigenfield(ef.id(), "Main Magnet");

        assertEquals("Main Magnet", renamed.name());
        assertEquals("Main Magnet", ((EigenfieldDocument) repo.node(ef.id())).name());
    }

    @Test
    void updateEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "desc", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);

        var updated = ef.withPreset(EigenfieldPreset.BIOT_SAVART_HELMHOLTZ);
        repo.updateEigenfield(updated);

        assertEquals(EigenfieldPreset.BIOT_SAVART_HELMHOLTZ,
            ((EigenfieldDocument) repo.node(ef.id())).preset());
    }

    @Test
    void addEigenfieldIsIdempotent() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "desc", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);
        repo.addEigenfield(ef);

        assertEquals(1, repo.eigenfieldIds().size());
    }

    // --- SimConfig CRUD ---

    @Test
    void renameSimConfig() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "d", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5,
            List.of(new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, ef.id())),
            List.of());
        var doc = new SimulationConfigDocument(new ProjectNodeId("sc-1"), "Old Name", config);
        repo.addSimConfig(doc);

        var renamed = repo.renameSimConfig(doc.id(), "New Name");

        assertEquals("New Name", renamed.name());
        assertEquals("New Name", repo.node(doc.id()).name());
    }

    @Test
    void simConfigLookupById() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-1"), "B0", "d", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5,
            List.of(new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, ef.id())),
            List.of());
        var doc = new SimulationConfigDocument(new ProjectNodeId("sc-1"), "Test", config);
        repo.addSimConfig(doc);

        assertNotNull(repo.simConfig(doc.id()));
        assertEquals("Test", repo.simConfig(doc.id()).name());
        assertNull(repo.simConfig(new ProjectNodeId("nonexistent")));
    }

    // --- Promotion creates sim config ---

    @Test
    void promotingSnapshotCreatesSimConfigWithEigenfields() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        // Open a non-iterative scenario (Alpha Capture)
        var alphaScenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> !node.iterative())
            .findFirst()
            .orElseThrow();
        session.openNode(alphaScenario.id());

        // Promote to sequence
        session.promoteActiveSnapshotToSequence();

        var repo = session.repository.get();
        // Should have a sequence
        assertEquals(1, repo.sequenceIds().size());
        var seqId = repo.sequenceIds().getFirst();

        // The sequence should have an active sim config ID
        var seqDoc = (SequenceDocument) repo.node(seqId);
        assertNotNull(seqDoc.activeSimConfigId(), "Promotion should set activeSimConfigId on the sequence");
        var simConfig = repo.simConfig(seqDoc.activeSimConfigId());
        assertNotNull(simConfig, "Promotion should auto-create a sim config");
        assertNotNull(simConfig.config());

        // Sim config should have 4 field definitions
        assertEquals(4, simConfig.config().fields().size());

        // Each field should reference a real eigenfield document
        for (var field : simConfig.config().fields()) {
            assertNotNull(field.eigenfieldId());
            var eigenNode = repo.node(field.eigenfieldId());
            assertNotNull(eigenNode, "Field '" + field.name() + "' references missing eigenfield " + field.eigenfieldId());
            assertInstanceOf(EigenfieldDocument.class, eigenNode);
        }

        // Eigenfields should exist in the repository
        assertTrue(repo.eigenfieldIds().size() >= 4, "Should have at least 4 eigenfields");
    }

    @Test
    void promotingFromRunCreatesSimConfigWithSourceParameters() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        // Open the iterative run (Beta Run)
        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();
        session.openNode(run.id());

        session.promoteActiveSnapshotToSequence();

        var repo = session.repository.get();
        var seqId = repo.sequenceIds().getFirst();
        var simConfigs = configForSequence(repo, seqId);
        assertEquals(1, simConfigs.size());

        var config = simConfigs.getFirst().config();
        // B0 should match the source BlochData field
        double expectedB0 = 1.5; // from TestBlochDataFactory.sampleField().b0n
        assertEquals(expectedB0, config.b0Tesla(), 1e-10);
        assertEquals(267.5e6, config.gamma(), 1e3);
    }

    @Test
    void createDefaultSimConfigCreatesEigenfieldsAndConfig() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session,"Test Config", 0.0154);
        var repo = session.repository.get();

        assertNotNull(doc);
        assertEquals("Test Config", doc.name());
        assertNotNull(doc.config());
        assertTrue(repo.simConfigIds().contains(doc.id()));

        // Should have 4 fields
        assertEquals(4, doc.config().fields().size());

        // B0 field should have the right amplitude
        assertEquals(0.0154, doc.config().b0Tesla(), 1e-10);

        // RF field should have non-zero baseband
        var rfField = doc.config().fields().stream()
            .filter(f -> f.name().equals("RF"))
            .findFirst().orElseThrow();
        assertTrue(rfField.basebandFrequencyHz() > 0, "RF should have non-zero baseband frequency");

        // All eigenfield references should be valid
        for (var field : doc.config().fields()) {
            assertInstanceOf(EigenfieldDocument.class, repo.node(field.eigenfieldId()));
        }
    }

    @Test
    void createMultipleConfigsReusesEigenfields() {
        var session = new ProjectSessionViewModel();
        createConfig(session,"Config 1", 0.0154);
        int eigenfieldCountAfterFirst = session.repository.get().eigenfieldIds().size();

        createConfig(session,"Config 2", 3.0);
        int eigenfieldCountAfterSecond = session.repository.get().eigenfieldIds().size();

        assertEquals(eigenfieldCountAfterFirst, eigenfieldCountAfterSecond,
            "Second config should reuse existing eigenfields, not duplicate them");
    }

    // --- BlochDataFactory with field-based config ---

    @Test
    void blochDataFactoryProducesValidDataFromFieldConfig() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(new ProjectNodeId("ef-b0"), "B0", "d", EigenfieldPreset.UNIFORM_BZ);
        repo.addEigenfield(ef);

        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5,
            List.of(new FieldDefinition("B0", ControlType.BINARY, 0, 1.5, 0, ef.id())),
            List.of(new SimulationConfig.IsoPoint(0, 0, "Centre", "#ff0000")));

        var segments = List.of(new Segment(1e-6, 0, 2), new Segment(1e-6, 2, 0));
        var data = BlochDataFactory.build(config, segments, repo);

        assertNotNull(data);
        assertNotNull(data.field());
        assertEquals(1.5, data.field().b0n, 1e-10);
        assertEquals(267.522e6, data.field().gamma, 1e3);
        assertEquals(1.0, data.field().t1, 1e-10); // 1000ms → 1.0s
        assertEquals(0.1, data.field().t2, 1e-10); // 100ms → 0.1s
        assertNotNull(data.field().dBzUt);
        assertNotNull(data.field().mz0);
        assertEquals(1, data.iso().size());
    }

    @Test
    void blochDataFactoryWorksWithoutRepository() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5,
            List.of(new FieldDefinition("B0", ControlType.BINARY, 0, 3.0, 0, new ProjectNodeId("dummy"))),
            List.of());
        var segments = List.of(new Segment(1e-6, 0, 2));
        var data = BlochDataFactory.build(config, segments);

        assertNotNull(data);
        assertEquals(3.0, data.field().b0n, 1e-10);
    }

    @Test
    void blochDataFactoryHandlesEmptyFieldList() {
        var config = new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5,
            List.of(), List.of());
        var segments = List.of(new Segment(1e-6, 0, 2));
        var data = BlochDataFactory.build(config, segments);

        assertNotNull(data);
        assertEquals(0.0, data.field().b0n);
    }

    // --- End-to-end: promotion → BlochData build succeeds ---

    @Test
    void fullPromotionPathProducesSimulatableBlochData() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        // Open Alpha scenario
        var alphaScenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> !node.iterative())
            .findFirst()
            .orElseThrow();
        session.openNode(alphaScenario.id());

        // Promote
        session.promoteActiveSnapshotToSequence();
        var repo = session.repository.get();
        var seqId = repo.sequenceIds().getFirst();
        var sequence = (SequenceDocument) repo.node(seqId);

        // The sim config should be loadable and produce a valid BlochData
        var simConfigs = configForSequence(repo, seqId);
        assertEquals(1, simConfigs.size());
        var simConfig = simConfigs.getFirst();

        // Build BlochData exactly as SequenceSimulationSession.simulate() would
        var data = BlochDataFactory.build(simConfig.config(), sequence.segments(), repo);
        assertNotNull(data, "BlochData must not be null");
        assertNotNull(data.field(), "BlochData.field must not be null");
        assertTrue(data.field().b0n > 0, "B0 must be positive");
        assertNotNull(data.field().dBzUt, "dBz map must exist");
        assertNotNull(data.field().mz0, "Initial magnetisation must exist");
        assertEquals(sequence.segments(), data.field().segments, "Segments must match");
    }

    @Test
    void promotionFromRunProducesSimulatableBlochData() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();
        session.openNode(run.id());
        session.promoteActiveSnapshotToSequence();

        var repo = session.repository.get();
        var seqId = repo.sequenceIds().getFirst();
        var sequence = (SequenceDocument) repo.node(seqId);
        var simConfigs = configForSequence(repo, seqId);
        var data = BlochDataFactory.build(simConfigs.getFirst().config(), sequence.segments(), repo);

        assertNotNull(data);
        assertNotNull(data.field());
        // Source was TestBlochDataFactory.sampleField() which has b0n=1.5
        assertEquals(1.5, data.field().b0n, 1e-10);
    }

    @Test
    void autoCreatedConfigFieldsReferenceValidEigenfieldsInRepository() {
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
        var config = configForSequence(repo, seqId).getFirst().config();

        // Verify field structure: B0 (binary DC), GradX (linear DC), GradZ (linear DC), RF (linear non-DC)
        assertEquals(4, config.fields().size());

        var b0 = config.fields().get(0);
        assertEquals(ControlType.BINARY, b0.controlType());
        assertTrue(b0.isDC());
        assertTrue(b0.maxAmplitude() > 0);

        var gradX = config.fields().get(1);
        assertEquals(ControlType.LINEAR, gradX.controlType());
        assertTrue(gradX.isDC());
        assertEquals(1, gradX.controlChannelCount());

        var gradZ = config.fields().get(2);
        assertEquals(ControlType.LINEAR, gradZ.controlType());
        assertTrue(gradZ.isDC());

        var rf = config.fields().get(3);
        assertEquals(ControlType.LINEAR, rf.controlType());
        assertFalse(rf.isDC());
        assertEquals(2, rf.controlChannelCount());
        assertTrue(rf.basebandFrequencyHz() > 0);

        // Every field must reference a valid eigenfield
        for (var field : config.fields()) {
            var eigenNode = repo.node(field.eigenfieldId());
            assertNotNull(eigenNode, "Missing eigenfield for " + field.name());
            assertInstanceOf(EigenfieldDocument.class, eigenNode);
            var eigen = (EigenfieldDocument) eigenNode;
            assertNotNull(eigen.preset());
        }
    }

    // --- Delete cascading / consistency ---

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

        // The sim config is top-level, not a child of the sequence, so it persists
        // (this is correct — sim configs are independent top-level objects)
        session.deleteSequence(seqId);
        assertTrue(repo.sequenceIds().isEmpty());
        // Sim config still exists (it's not deleted with the sequence)
        assertFalse(repo.simConfigIds().isEmpty());
    }

    @Test
    void deleteSimConfigRemovesFromRepository() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session,"Test", 3.0);
        var repo = session.repository.get();
        assertTrue(repo.simConfigIds().contains(doc.id()));

        session.deleteSimConfig(doc.id());
        assertFalse(repo.simConfigIds().contains(doc.id()));
    }

    @Test
    void deleteEigenfieldRemovesFromRepository() {
        var session = new ProjectSessionViewModel();
        createConfig(session,"Test", 3.0);
        var repo = session.repository.get();
        var efId = repo.eigenfieldIds().getFirst();
        assertTrue(repo.eigenfieldIds().contains(efId));

        session.deleteEigenfield(efId);
        assertFalse(repo.eigenfieldIds().contains(efId));
    }

    @Test
    void renameSimConfigUpdatesNameInRepository() {
        var session = new ProjectSessionViewModel();
        var doc = createConfig(session,"Old Name", 3.0);
        session.renameSimConfig(doc.id(), "New Name");
        assertEquals("New Name", session.repository.get().node(doc.id()).name());
    }

    @Test
    void renameEigenfieldUpdatesNameInRepository() {
        var session = new ProjectSessionViewModel();
        createConfig(session,"Test", 3.0);
        var repo = session.repository.get();
        var efId = repo.eigenfieldIds().getFirst();

        session.renameEigenfield(efId, "Renamed EF");
        assertEquals("Renamed EF", repo.node(efId).name());
    }
}
