package ax.xz.mri.project;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.SimulationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        var circuitId = new ProjectNodeId("circuit-1");
        repo.addCircuit(CircuitDocument.empty(circuitId, "Test Circuit"));

        var cfg = new SimulationConfig(
            1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 1e-6, circuitId);
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
            new ProjectNodeId("ef-1"), "E", "desc", "return Vec3.ZERO;", "T");
        repo.addEigenfield(ef);
        assertNotNull(repo.node(ef.id()));
        repo.removeEigenfield(ef.id());
        assertNull(repo.node(ef.id()));
        assertTrue(repo.eigenfieldIds().isEmpty());
    }

    @Test
    void addUpdateAndRemoveCircuit() {
        var repo = ProjectRepository.untitled();
        var id = new ProjectNodeId("circuit-7");
        var original = CircuitDocument.empty(id, "Draft");
        repo.addCircuit(original);
        assertEquals(List.of(id), repo.circuitIds());
        assertEquals(original, repo.circuit(id));

        var coil = new CircuitComponent.Coil(
            new ComponentId("coil-0"), "C0", null, 0, 1);
        var updated = original.addComponent(coil, null);
        repo.updateCircuit(updated);
        assertEquals(updated, repo.circuit(id));

        repo.removeCircuit(id);
        assertNull(repo.circuit(id));
        assertTrue(repo.circuitIds().isEmpty());
    }

    @Test
    void renameCircuit() {
        var repo = ProjectRepository.untitled();
        var id = new ProjectNodeId("circuit-8");
        repo.addCircuit(CircuitDocument.empty(id, "Draft"));
        var renamed = repo.renameCircuit(id, "Final");
        assertEquals("Final", renamed.name());
        assertEquals("Final", repo.circuit(id).name());
    }
}
