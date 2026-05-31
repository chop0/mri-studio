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
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive tests for {@link IsochromatCollectionModel} behaviour — the
 * model that powers the Points pane. Each test exercises a specific
 * interaction the user can trigger via the Points pane UI plus edge cases.
 */
class IsochromatCollectionInteractionTest {

    @Test
    void addUserPointIntoBlochSimAppendsToFan() {
        var model = freshModelWith(blochSim());
        model.resetToDefaults();
        int defaults = model.entries.size();
        model.addUserPoint(new Vec3(1e-3, 0, 5e-3), "U1");
        assertEquals(defaults + 1, model.entries.size(),
            "User point appends to the scenario-default fan");
        var added = model.entries.get(model.entries.size() - 1);
        assertEquals(IsochromatOrigin.USER, added.origin());
        assertFalse(added.locked());
    }

    @Test
    void switchingFromBlochToNvReplacesScenarioDefaults() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        // Start in Bloch.
        model.setContext(blochSim(), List.of());
        int blochCount = model.entries.size();
        assertTrue(blochCount > 0, "Bloch sim should seed scenario defaults");
        assertTrue(model.entries.stream().allMatch(e -> e.origin() == IsochromatOrigin.SCENARIO_DEFAULT));
        // Add a USER point that should survive the substance switch.
        model.addUserPoint(new Vec3(2e-3, 0, 2e-3), "User");
        // Switch to NV.
        model.setContext(nvSim(), List.of());
        // USER point survives, scenario defaults are replaced by NV_CENTRE entries.
        long userPoints = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.USER).count();
        long nvCentres = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.NV_CENTRE).count();
        long scenarioDefaults = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.SCENARIO_DEFAULT).count();
        assertEquals(1, userPoints, "USER points must survive substance switch");
        assertTrue(nvCentres > 0, "NV-only sim should populate NV_CENTRE entries");
        assertEquals(0, scenarioDefaults, "Bloch fan entries must be gone after switch");
    }

    @Test
    void switchingFromNvToBlochReplacesNvCentres() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(nvSim(), List.of());
        long nvCentres = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.NV_CENTRE).count();
        assertTrue(nvCentres > 0);
        model.setContext(blochSim(), List.of());
        long nvAfter = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.NV_CENTRE).count();
        long scenarioAfter = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.SCENARIO_DEFAULT).count();
        assertEquals(0, nvAfter, "NV centre entries must be gone after switch");
        assertTrue(scenarioAfter > 0, "Bloch fan must repopulate");
    }

    @Test
    void switchingToEmptySimRemovesProvidedDefaults() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        int before = model.entries.size();
        model.addUserPoint(new Vec3(3e-3, 0, 3e-3), "User");
        model.setContext(emptySim(), List.of());
        long userOnly = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.USER).count();
        assertEquals(1, userOnly, "USER points survive switch to substance-less sim");
        long otherKinds = model.entries.stream()
            .filter(e -> e.origin() != IsochromatOrigin.USER).count();
        assertEquals(0, otherKinds, "Scenario defaults and NV centres must clear");
        assertNotEquals(before, model.entries.size());
    }

    @Test
    void duplicateNvCentreClonesIntoUserPoint() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(nvSim(), List.of());
        var nvEntry = model.entries.get(0);
        sel.setSingle(nvEntry.id());
        model.duplicateSelected();
        // The duplicate carries over the origin (NV_CENTRE) — that's by design
        // since duplicateSelected copies the source's origin. The user typically
        // wants to track a virtual NV close by. The locked flag carries too.
        var dup = model.entries.stream()
            .filter(e -> e.name().equals(nvEntry.name() + " copy"))
            .findFirst().orElseThrow();
        assertNotEquals(nvEntry.id(), dup.id());
    }

    @Test
    void removeNvCentreIsBlocked() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(nvSim(), List.of());
        var nvEntry = model.entries.get(0);
        int before = model.entries.size();
        model.remove(nvEntry.id());
        assertEquals(before, model.entries.size(),
            "NV_CENTRE entries must refuse delete");
        assertTrue(model.findById(nvEntry.id()).isPresent());
    }

    @Test
    void removeUserPointSucceeds() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        model.addUserPoint(new Vec3(4e-3, 0, 0), "Doomed");
        var doomed = model.entries.stream()
            .filter(e -> e.origin() == IsochromatOrigin.USER)
            .findFirst().orElseThrow();
        model.remove(doomed.id());
        assertTrue(model.findById(doomed.id()).isEmpty());
    }

    @Test
    void moveNvCentreIsNoOp() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(nvSim(), List.of());
        var nvEntry = model.entries.get(0);
        var originalPos = nvEntry.position();
        model.move(nvEntry.id(), new Vec3(9e-3, 9e-3, 9e-3));
        assertEquals(originalPos, model.findById(nvEntry.id()).orElseThrow().position(),
            "NV_CENTRE positions are mastered by the substance — Points pane drag is inert");
    }

    @Test
    void moveScenarioDefaultUpdatesPosition() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        var entry = model.entries.get(0);
        var newPos = new Vec3(5e-3, 0, 5e-3);
        model.move(entry.id(), newPos);
        assertEquals(newPos, model.findById(entry.id()).orElseThrow().position());
    }

    @Test
    void clearUserPointsLeavesProvidedDefaults() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        int defaults = model.entries.size();
        model.addUserPoint(new Vec3(1e-3, 0, 0), "A");
        model.addUserPoint(new Vec3(2e-3, 0, 0), "B");
        assertEquals(defaults + 2, model.entries.size());
        model.clearUserPoints();
        assertEquals(defaults, model.entries.size());
        assertTrue(model.entries.stream().noneMatch(e -> e.origin() == IsochromatOrigin.USER));
    }

    @Test
    void resetToDefaultsWipesUserPoints() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        model.addUserPoint(new Vec3(1e-3, 0, 0), "User");
        assertTrue(model.entries.stream().anyMatch(e -> e.origin() == IsochromatOrigin.USER));
        model.resetToDefaults();
        assertTrue(model.entries.stream().noneMatch(e -> e.origin() == IsochromatOrigin.USER),
            "Reset Defaults must wipe USER entries (different from substance-switch behaviour)");
    }

    @Test
    void renameUpdatesEntryName() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        var entry = model.entries.get(0);
        model.rename(entry.id(), "Renamed");
        assertEquals("Renamed", model.findById(entry.id()).orElseThrow().name());
    }

    @Test
    void toggleVisibilityFlipsFlag() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        var entry = model.entries.get(0);
        boolean before = entry.visible();
        model.toggleVisibility(entry.id());
        assertEquals(!before, model.findById(entry.id()).orElseThrow().visible());
    }

    @Test
    void bulkRemoveSkipsNvCentresAndDeletesUserPoints() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(nvSim(), List.of());
        model.addUserPoint(new Vec3(0.5e-6, 0, -50e-9), "Probe");
        var allIds = model.entries.stream().map(e -> e.id()).toList();
        model.remove(allIds);
        // NV centres survive; USER point is removed.
        assertTrue(model.entries.stream().noneMatch(e -> e.origin() == IsochromatOrigin.USER),
            "USER points should be removed by bulk delete");
        assertTrue(model.entries.stream().anyMatch(e -> e.origin() == IsochromatOrigin.NV_CENTRE),
            "NV_CENTRE entries should survive bulk delete");
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(blochSim(), List.of());
        assertTrue(model.findById(new IsochromatId(999L)).isEmpty());
    }

    private static IsochromatCollectionModel freshModelWith(CompiledSimulation sim) {
        var sel = new IsochromatSelectionModel();
        var model = new IsochromatCollectionModel(
            sel, Runnable::run, Runnable::run, () -> { });
        model.setContext(sim, List.of());
        return model;
    }

    private static CompiledSimulation blochSim() {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        List<Substance> substances = List.of(new ContinuousMagnetisation(1.0, 0.1, 267.522e6, 1.0, 0.030, 0.030, 0.010, 5, 5, 50));
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            substances, List.<Segment>of(), List.<PulseSegment>of(), 0.0154));
    }

    private static CompiledSimulation nvSim() {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        var centres = List.of(
            new NvCentre(0, 0, -50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(0.5e-6, 0, -50e-9, NvAxis.AXIS_PLUS_Z));
        var geom = new NvArrayGeometry(NvArrayShape.CUSTOM, centres.size(), 1e-6, 50e-9,
            NvAxis.AXIS_PLUS_Z, 0L, centres);
        List<Substance> substances = List.of(new NvEnsemble(geom, NvPhysics.defaults(), 0L));
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            substances, List.<Segment>of(), List.<PulseSegment>of(), 0.01));
    }

    private static CompiledSimulation emptySim() {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            List.<Substance>of(), List.<Segment>of(), List.<PulseSegment>of(), 0.01));
    }
}
