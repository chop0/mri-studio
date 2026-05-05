package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks foreign-key references in a {@link ProjectState} and clears any that
 * point at a now-missing node. Run inside {@link UnifiedStateManager#dispatch}
 * after applying the user's mutation, so dangling references are cleaned up
 * in the same atomic step (eager cascade — undo restores everything).
 *
 * <p>The references this class tracks today are:
 * <ul>
 *   <li>{@link SequenceDocument#activeSimConfigId} → simulation</li>
 *   <li>{@link SequenceDocument#preferredHardwareConfigId} → hardware</li>
 *   <li>{@link SimulationConfigDocument#config()} {@code .circuitId()} → circuit</li>
 *   <li>{@link CircuitComponent.Coil#eigenfieldId()} → eigenfield</li>
 * </ul>
 */
public final class RefIntegrity {

    private RefIntegrity() {}

    /**
     * Returns a state with all dangling FKs nulled out, plus the list of
     * cascade fix-ups applied. If nothing changed, returns the input state
     * unchanged and an empty list.
     */
    public static Result validate(ProjectState state) {
        var fixes = new ArrayList<String>();
        var current = state;

        // 1. Sequence FKs
        for (var seqId : new ArrayList<>(current.sequenceIds())) {
            var seq = current.sequence(seqId);
            if (seq == null) continue;
            var newSeq = seq;
            if (seq.activeSimConfigId() != null && !current.simulations().containsKey(seq.activeSimConfigId())) {
                newSeq = new SequenceDocument(
                    newSeq.id(), newSeq.name(), newSeq.clipSequence(),
                    null, newSeq.preferredHardwareConfigId());
                fixes.add("Cleared activeSimConfigId on sequence " + seq.id());
            }
            if (seq.preferredHardwareConfigId() != null && !current.hardware().containsKey(seq.preferredHardwareConfigId())) {
                newSeq = new SequenceDocument(
                    newSeq.id(), newSeq.name(), newSeq.clipSequence(),
                    newSeq.activeSimConfigId(), null);
                fixes.add("Cleared preferredHardwareConfigId on sequence " + seq.id());
            }
            if (newSeq != seq) {
                current = current.withSequence(newSeq);
            }
        }

        // 2. SimConfig.circuitId — null the FK if missing.
        for (var cfgId : new ArrayList<>(current.simulationIds())) {
            var cfg = current.simulation(cfgId);
            if (cfg == null) continue;
            var inner = cfg.config();
            if (inner != null && inner.circuitId() != null
                && !current.circuits().containsKey(inner.circuitId())) {
                var newInner = inner.withCircuitId(null);
                var newCfg = new SimulationConfigDocument(cfg.id(), cfg.name(), newInner);
                current = current.withSimulation(newCfg);
                fixes.add("Cleared circuitId on simconfig " + cfg.id());
            }
        }

        // 3. Coil.eigenfieldId — null the FK if missing. Each affected circuit
        //    is rewritten with the coil's eigenfieldId set to null.
        for (var circuitId : new ArrayList<>(current.circuitIds())) {
            var circuit = current.circuit(circuitId);
            if (circuit == null) continue;
            boolean changed = false;
            var newComponents = new ArrayList<CircuitComponent>();
            for (var comp : circuit.components()) {
                if (comp instanceof CircuitComponent.Coil coil
                    && coil.eigenfieldId() != null
                    && !current.eigenfields().containsKey(coil.eigenfieldId())) {
                    var fixed = new CircuitComponent.Coil(
                        coil.id(), coil.name(), null,
                        coil.selfInductanceHenry(), coil.seriesResistanceOhms(),
                        coil.sensitivityT_per_A());
                    newComponents.add(fixed);
                    changed = true;
                    fixes.add("Cleared eigenfieldId on coil " + coil.id() + " in circuit " + circuit.id());
                } else {
                    newComponents.add(comp);
                }
            }
            if (changed) {
                var newCircuit = new CircuitDocument(
                    circuit.id(), circuit.name(),
                    newComponents, circuit.wires(), circuit.layout());
                current = current.withCircuit(newCircuit);
            }
        }

        return new Result(current, List.copyOf(fixes));
    }

    public record Result(ProjectState state, List<String> fixes) {
        public boolean changed() { return !fixes.isEmpty(); }
    }
}
