package ax.xz.mri.project;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.ReceiveCoil;
import ax.xz.mri.model.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepositoryTest {

    @Test
    void addAndRetrieveSequence() {
        var repo = ProjectRepository.untitled();
        var seq = new SequenceDocument(
            new ProjectNodeId("seq-1"), "My Seq",
            List.of(), List.of(), null, null);
        repo.addSequence(seq);

        assertEquals(List.of(seq.id()), repo.sequenceIds());
        assertEquals(seq, repo.node(seq.id()));
        assertTrue(repo.contains(seq.id()));
    }

    @Test
    void addAndRenameSimConfig() {
        var repo = ProjectRepository.untitled();
        var eigenId = new ProjectNodeId("ef-1");
        repo.addEigenfield(new EigenfieldDocument(
            eigenId, "E", "desc", "return Vec3.of(1,0,0);", "T", 1.0));

        var rx = new ReceiveCoil("RX", eigenId, 1.0, 0.0);
        var cfg = new SimulationConfig(
            1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 1e-6,
            List.of(new FieldDefinition("Drive", eigenId, AmplitudeKind.REAL, 0, -1, 1)),
            List.of(rx));
        var doc = new SimulationConfigDocument(new ProjectNodeId("simcfg-1"), "Cfg A", cfg);
        repo.addSimConfig(doc);
        assertEquals(doc, repo.simConfig(doc.id()));

        var renamed = repo.renameSimConfig(doc.id(), "Cfg B");
        assertEquals("Cfg B", renamed.name());
        assertEquals("Cfg B", repo.simConfig(doc.id()).name());
    }

    @Test
    void removeEigenfield() {
        var repo = ProjectRepository.untitled();
        var ef = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "E", "desc", "return Vec3.ZERO;", "T", 1.0);
        repo.addEigenfield(ef);
        assertNotNull(repo.node(ef.id()));
        repo.removeEigenfield(ef.id());
        assertNull(repo.node(ef.id()));
        assertTrue(repo.eigenfieldIds().isEmpty());
    }
}
