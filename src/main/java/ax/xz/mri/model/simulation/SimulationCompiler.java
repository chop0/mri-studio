package ax.xz.mri.model.simulation;

import module ax.xz.mri;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.state.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a project-level {@link SimulationConfig} + baked sequence into
 * a {@link CompiledSimulation}.
 *
 * <p>Substances come from the circuit's {@link CircuitComponent.Substance
 * Substance} blocks — each resolves its {@link ax.xz.mri.project.SubstanceDocument}
 * from the project repository. A circuit without substance blocks compiles
 * to an empty-substance simulation; the analysis panes detect this and gate
 * accordingly (cross-section paints the B-field placeholder, Points pane
 * stays empty, the Add Point toolbar disables).
 *
 * <p>There is no silent "default proton" fallback — adding a continuous
 * magnetisation without the user's say-so produced exactly the kind of
 * pretend-data-where-there-is-none lie Part 11 was written to eliminate.
 */
public final class SimulationCompiler {

    public CompiledSimulation compile(
        SimulationConfig cfg,
        List<Segment> segments,
        List<PulseSegment> pulse,
        ProjectState repository
    ) {
        var circuit = resolveCircuit(cfg, repository);
        var substances = buildSubstances(circuit, repository);
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(
            circuit, repository, substances, segments, pulse,
            cfg.referenceB0Tesla(), cfg.methods().nv()
        ));
    }

    private static CircuitDocument resolveCircuit(SimulationConfig cfg, ProjectState repository) {
        if (repository == null || cfg.circuitId() == null) return null;
        return repository.node(cfg.circuitId()) instanceof CircuitDocument c ? c : null;
    }

    private static List<Substance> buildSubstances(CircuitDocument circuit, ProjectState repository) {
        if (circuit == null || repository == null) return List.of();
        var list = new ArrayList<Substance>();
        for (var comp : circuit.components()) {
            if (comp instanceof CircuitComponent.Substance block) {
                var doc = repository.substance(block.substanceDocId());
                if (doc != null) list.add(doc.substance());
            }
        }
        return list;
    }
}
