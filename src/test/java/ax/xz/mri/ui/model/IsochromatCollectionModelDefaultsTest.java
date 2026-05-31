package ax.xz.mri.ui.model;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.support.TestSimulationFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Substance-aware default population for the Points pane.
 *
 * <p>Per Part 11 of the rebuild plan: the entry list is sourced from whatever
 * substance is in the FOV. Continuous magnetisation → built-in Bloch fan,
 * movable. NV-only → centres of the first NV ensemble, locked + badged
 * {@link IsochromatOrigin#NV_CENTRE}. Empty substance list → empty entries.
 */
class IsochromatCollectionModelDefaultsTest {

    @Test
    void blochSimulationProducesScenarioDefaultFan() {
        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(TestSimulationFactory.sampleSimulation(), TestSimulationFactory.pulseA());
        points.resetToDefaults();

        assertFalse(points.entries.isEmpty(), "Bloch sim should produce a non-empty fan");
        assertTrue(
            points.entries.stream().allMatch(entry -> entry.origin() == IsochromatOrigin.SCENARIO_DEFAULT),
            "All entries from a Bloch sim are scenario defaults");
        assertTrue(
            points.entries.stream().noneMatch(IsochromatEntry::locked),
            "Scenario defaults are movable — not locked");
    }

    @Test
    void nvOnlySimulationProducesLockedNvCentreEntries() {
        var centres = List.of(
            new NvCentre(0, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(5e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(10e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)
        );
        var sim = compileNvOnly(centres);

        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(sim, List.of());
        points.resetToDefaults();

        assertEquals(centres.size(), points.entries.size(),
            "NV-only sim should produce one entry per centre");
        assertTrue(
            points.entries.stream().allMatch(entry -> entry.origin() == IsochromatOrigin.NV_CENTRE),
            "All entries from an NV-only sim are NV_CENTRE-tagged");
        assertTrue(
            points.entries.stream().allMatch(IsochromatEntry::locked),
            "NV centre entries are locked — substance editor owns them");
        // Positions match the centres exactly.
        for (int i = 0; i < centres.size(); i++) {
            var c = centres.get(i);
            var p = points.entries.get(i).position();
            assertEquals(c.xMetres(), p.x(), 1e-15);
            assertEquals(c.yMetres(), p.y(), 1e-15);
            assertEquals(c.zMetres(), p.z(), 1e-15);
        }
    }

    @Test
    void emptySubstanceListProducesEmptyEntries() {
        // No substances → nothing to observe.
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        var sim = CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            List.<Substance>of(), List.<Segment>of(), List.<PulseSegment>of(), 0.01));

        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(sim, List.of());
        points.resetToDefaults();

        assertTrue(points.entries.isEmpty(), "No substance → no default entries");
    }

    @Test
    void nvCentreEntriesRefuseMutation() {
        var centres = List.of(new NvCentre(0, 0, 50e-9, NvAxis.AXIS_PLUS_Z));
        var sim = compileNvOnly(centres);

        var selection = new IsochromatSelectionModel();
        var points = new IsochromatCollectionModel(selection, Runnable::run, Runnable::run, () -> { });
        points.setContext(sim, List.of());
        points.resetToDefaults();

        var nvEntry = points.entries.get(0);
        var nvId = nvEntry.id();
        var originalPos = nvEntry.position();

        // move on NV_CENTRE is a no-op.
        points.move(nvId, new ax.xz.mri.model.simulation.Vec3(1e-3, 0, 0));
        assertEquals(originalPos, points.findById(nvId).orElseThrow().position(),
            "NV_CENTRE entries don't budge — the substance editor owns their position");

        // bulk-remove skips NV_CENTRE entries.
        points.remove(List.of(nvId));
        assertTrue(points.findById(nvId).isPresent(),
            "NV_CENTRE entries can't be deleted via the Points pane");
    }

    private static CompiledSimulation compileNvOnly(List<NvCentre> centres) {
        var geom = new NvArrayGeometry(NvArrayShape.CUSTOM, centres.size(), 1e-6, 50e-9,
            NvAxis.AXIS_PLUS_Z, 0L, centres);
        var ensemble = new NvEnsemble(geom, NvPhysics.defaults(), 0L);
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        List<Substance> substances = List.of(ensemble);
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            substances, List.<Segment>of(), List.<PulseSegment>of(), 0.01));
    }
}
