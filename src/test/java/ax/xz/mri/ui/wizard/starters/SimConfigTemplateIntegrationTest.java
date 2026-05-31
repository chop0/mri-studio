package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.starter.CircuitStarter;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for every {@link SimConfigTemplate}. Each
 * template's circuit starter must:
 *
 * <ol>
 *   <li>produce a valid {@link ax.xz.mri.model.circuit.CircuitDocument} (no
 *       wire-validation throw, no unknown component types);</li>
 *   <li>contain the components the template promises (a B0 source for
 *       LOW_FIELD_MRI, a Substance block + optical counter for
 *       NV_CENTRE_DIAMOND, etc.);</li>
 *   <li>compile end-to-end into a {@link
 *       ax.xz.mri.service.simulation.compiled.CompiledSimulation} through
 *       {@link SimulationCompiler} — every referenced eigenfield script
 *       compiles, every wire resolves, every component stamps cleanly;</li>
 *   <li>run one tiny pulse through {@link
 *       ax.xz.mri.service.simulation.compiled.CompiledSimulation
 *       #singleSpinTrajectory} (or {@code runMultiProbe} for templates
 *       with probes) without throwing.</li>
 * </ol>
 *
 * <p>The test exercises the actual paths the wizard would take when the
 * user picks a template + clicks Finish.
 */
final class SimConfigTemplateIntegrationTest {

    @Test
    void emptyTemplateProducesAnEmptyCompilableCircuit() {
        var built = SimConfigTemplate.EMPTY.buildCircuit(ProjectState.empty(), "Empty test");
        assertNotNull(built.circuit());
        assertTrue(built.circuit().components().isEmpty(), "Empty starter has no components");
        assertTrue(built.circuit().wires().isEmpty(),      "Empty starter has no wires");
        assertTrue(built.newEigenfields().isEmpty());
        assertTrue(built.newSubstances().isEmpty());
        // Empty circuit still compiles into a CompiledSimulation (with no coils).
        var repo = ProjectState.empty()
            .withCircuit(built.circuit());
        var cfg = simConfig(SimConfigTemplate.EMPTY, built.circuit().id());
        var sim = new SimulationCompiler().compile(cfg, tinyPulse().segments(),
            tinyPulse().pulses(), repo);
        assertNotNull(sim);
        assertEquals(0, sim.circuit().coils().size());
        sim.runMultiProbe();   // probes empty too — just verifying no crash
    }

    @Test
    void lowFieldMriTemplateRoundTripsThroughCompilation() {
        var built = SimConfigTemplate.LOW_FIELD_MRI.buildCircuit(ProjectState.empty(), "MRI test");
        assertNotNull(built.circuit());
        // The starter must produce a circuit with B0/RF/Gx/Gz coils + a probe.
        long coils = built.circuit().components().stream()
            .filter(c -> c instanceof CircuitComponent.Coil).count();
        assertTrue(coils >= 4, "Expected ≥ 4 coils, got " + coils);
        long probes = built.circuit().components().stream()
            .filter(c -> c instanceof CircuitComponent.Probe).count();
        assertEquals(1, probes, "Expected one Probe in MRI starter");

        // Every newly-minted eigenfield must compile through the engine —
        // proves the starter sources are syntactically valid full-class form.
        for (var ef : built.newEigenfields()) {
            EigenfieldEngine.compile(ef.script());
        }

        var repo = installSatellites(built);
        var cfg = simConfig(SimConfigTemplate.LOW_FIELD_MRI, built.circuit().id());
        var pulse = tinyPulse(built.circuit().components());
        var sim = new SimulationCompiler().compile(cfg, pulse.segments(), pulse.pulses(), repo);
        assertNotNull(sim);
        assertEquals(coils, sim.circuit().coils().size());
        // One physics tick over the probe — should not throw, should populate the trace.
        var traces = sim.runMultiProbe();
        assertNotNull(traces);
    }

    @Test
    void nvCentreDiamondTemplateExposesSubstanceAndOpticalCounter() {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "NV test");
        assertNotNull(built.circuit());

        // Sources: B0 + Sample (STATIC) + MW I + MW Q + Grad X (REAL) + Laser (GATE) = 6.
        // Coils: B0 + Sample + MW + Grad X = 4.
        // Plus one modulator, one substance block, one optical counter.
        var byKind = built.circuit().components().stream()
            .collect(Collectors.groupingBy(c -> c.getClass().getSimpleName(),
                Collectors.counting()));
        assertEquals(Long.valueOf(6L), byKind.getOrDefault("VoltageSource", 0L),
            "B0 + Sample + MW I + MW Q + Grad X + Laser");
        assertEquals(Long.valueOf(4L), byKind.getOrDefault("Coil",          0L),
            "B0 + Sample + MW + Grad X coils");
        assertEquals(Long.valueOf(1L), byKind.getOrDefault("Modulator",     0L),
            "MW modulator");
        assertEquals(Long.valueOf(1L), byKind.getOrDefault("Substance",     0L),
            "Diamond substance block");
        assertEquals(Long.valueOf(1L), byKind.getOrDefault("OpticalCounter", 0L),
            "Red photon counter");

        // The substance block must be NV-kind and reference a freshly-minted
        // SubstanceDocument in newSubstances.
        var substanceBlock = (CircuitComponent.Substance) built.circuit().components().stream()
            .filter(c -> c instanceof CircuitComponent.Substance).findFirst().orElseThrow();
        assertEquals(CircuitComponent.Substance.Kind.NV, substanceBlock.kind());
        assertEquals(1, built.newSubstances().size(), "NV starter mints exactly one substance document");
        assertEquals(built.newSubstances().get(0).id(), substanceBlock.substanceDocId(),
            "Substance block must reference the new doc");

        // The optical counter must be wired from the substance's clicks_red.
        var counter = (CircuitComponent.OpticalCounter) built.circuit().components().stream()
            .filter(c -> c instanceof CircuitComponent.OpticalCounter).findFirst().orElseThrow();
        boolean wired = built.circuit().wires().stream().anyMatch(w ->
            (w.from().componentId().equals(substanceBlock.id())
                && w.from().port().equals("clicks_red")
                && w.to().componentId().equals(counter.id())
                && w.to().port().equals("in"))
            ||
            (w.to().componentId().equals(substanceBlock.id())
                && w.to().port().equals("clicks_red")
                && w.from().componentId().equals(counter.id())
                && w.from().port().equals("in"))
        );
        assertTrue(wired, "Substance.clicks_red must be wired to the optical counter's in port");

        // Compile end-to-end. Eigenfields compile, circuit compiles, the
        // substance block contributes no MNA (optical/control ports only),
        // and runMultiProbe returns a (possibly empty) trace bag.
        for (var ef : built.newEigenfields()) {
            EigenfieldEngine.compile(ef.script());
        }
        var repo = installSatellites(built);
        var cfg = simConfig(SimConfigTemplate.NV_CENTRE_DIAMOND, built.circuit().id());
        var pulse = tinyPulse(built.circuit().components());
        var sim = new SimulationCompiler().compile(cfg, pulse.segments(), pulse.pulses(), repo);
        assertNotNull(sim);
        assertEquals(4, sim.circuit().coils().size(), "B0 + Sample + MW + Grad X coils visible to the compiler");
        sim.runMultiProbe();
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static SimulationConfig simConfig(SimConfigTemplate template, ProjectNodeId circuitId) {
        var physics = template.defaultPhysics();
        return new SimulationConfig(template.referenceB0Tesla(), physics.dtSeconds(), circuitId);
    }

    private static ProjectState installSatellites(CircuitStarter.Result built) {
        var repo = ProjectState.empty();
        for (var ef : built.newEigenfields()) repo = repo.withEigenfield(ef);
        for (var sub : built.newSubstances()) repo = repo.withSubstance(sub);
        repo = repo.withCircuit(built.circuit());
        return repo;
    }

    /** Tiny pulse: one segment of one zero-valued step at every coil drive. */
    private static Pulse tinyPulse() {
        return tinyPulse(List.of());
    }

    private static Pulse tinyPulse(List<CircuitComponent> components) {
        int channelCount = 0;
        for (var c : components) {
            if (c instanceof CircuitComponent.VoltageSource src) {
                channelCount += src.kind().channelCount();
            }
        }
        var step = new PulseStep(new double[Math.max(channelCount, 1)], 0.0);
        var pulse = List.of(new PulseSegment(List.of(step)));
        var segments = List.of(new Segment(1.0e-6, 0, 1));
        return new Pulse(segments, pulse);
    }

    private record Pulse(List<Segment> segments, List<PulseSegment> pulses) {}
}
