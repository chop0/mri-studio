package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Round-trips a few realistic Mutations through the undo-log persistence layer. */
class UndoLogPersistenceTest {

    @Test
    void roundTripsMixedPayloadTypes(@TempDir Path tmp) {
        var io = new UndoLogPersistence();
        Deque<Mutation> log = new ArrayDeque<>();
        // 1. Eigenfield rename (whole-document replacement).
        var efBefore = new EigenfieldDocument(new ProjectNodeId("ef-1"),
            "B0", "main field", "return Vec3.of(0,0,1);", "T");
        var efAfter = efBefore.withName("B0 Helmholtz");
        log.push(new Mutation(
            Scope.indexed(Scope.root(), "eigenfields", efBefore.id()),
            efBefore, efAfter, "Rename eigenfield",
            Instant.parse("2026-05-04T12:00:00Z"), null, Mutation.Category.STRUCTURAL));
        // 2. Resistor value tweak (record subtype inside sealed interface).
        var resistorBefore = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0);
        var resistorAfter = resistorBefore.withResistanceOhms(220.0);
        log.push(new Mutation(
            Scope.field(
                Scope.indexed(
                    Scope.indexed(Scope.root(), "circuits", new ProjectNodeId("c1")),
                    "components", new ComponentId("R1")),
                "resistanceOhms"),
            100.0, 220.0, "Edit resistance",
            Instant.parse("2026-05-04T12:01:00Z"), "schematic", Mutation.Category.CONTENT));

        try {
            io.write(log, tmp);
            var loaded = io.read(tmp);
            assertEquals(2, loaded.size(), "expected two entries");
            // Order: file is written front-to-back of the deque, which is
            // most-recent-first; the loaded list mirrors that.
            assertEquals("Edit resistance", loaded.get(0).label());
            assertEquals("Rename eigenfield", loaded.get(1).label());
            // Payload types survived round-trip.
            assertNotNull(loaded.get(0).before());
            assertEquals(220.0, loaded.get(0).after());
            assertEquals(efAfter, loaded.get(1).after());
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void readReturnsEmptyWhenFileMissing(@TempDir Path tmp) {
        var io = new UndoLogPersistence();
        var loaded = io.read(tmp);
        assertEquals(List.of(), loaded);
    }
}
