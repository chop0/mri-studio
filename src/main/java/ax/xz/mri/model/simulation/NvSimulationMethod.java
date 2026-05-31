package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The numerical <em>technique</em> used to evolve an NV-centre ensemble — a
 * property of the simulation <em>environment</em>, not of the material. (Just
 * as {@link SimulationConfig}'s doc says tissue physics lives on the substance
 * and not the config, the converse holds: how finely we couple/cluster NVs is
 * a method choice and lives here, reachable from the config via
 * {@link SimulationMethods}.)
 *
 * <p>Sealed so the set of techniques is explicit and pattern-matchable at
 * compile time. v1 ships one technique — {@link ClusteredQubitHamiltonian} —
 * with the fully-independent classical model expressed as its
 * {@code maxClusterSize == 1} degenerate case. Richer techniques (a full
 * spin-1 / zero-field-splitting Hamiltonian, a Monte-Carlo wavefunction
 * unravelling, …) slot in as additional permitted records without touching
 * call sites that already switch over this type.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@technique")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NvSimulationMethod.ClusteredQubitHamiltonian.class, name = "clustered_qubit_hamiltonian")
})
public sealed interface NvSimulationMethod {

    /**
     * Effective-qubit clustered Hamiltonian. NV centres closer than
     * {@code couplingCutoffMetres} are grouped (compile-time union-find) and
     * evolved <em>jointly</em> as a {@code 2^k} density matrix carrying the
     * secular magnetic-dipolar coupling between cluster members; the cap
     * {@code maxClusterSize} bounds {@code k} (larger physical groups are
     * spatially sub-split, dropping the weak inter-block coupling with a logged
     * warning). A {@code maxClusterSize == 1} (or zero cutoff) cluster is the
     * cheap, fully-independent classical Bloch path and reproduces the
     * single-NV model exactly.
     *
     * @param maxClusterSize       cap on jointly-simulated NVs per cluster (≥ 1)
     * @param couplingCutoffMetres pairs closer than this couple; ≤ 0 disables coupling
     */
    record ClusteredQubitHamiltonian(int maxClusterSize, double couplingCutoffMetres)
        implements NvSimulationMethod {

        public ClusteredQubitHamiltonian {
            if (maxClusterSize < 1)
                throw new IllegalArgumentException("maxClusterSize must be ≥ 1, got " + maxClusterSize);
            if (!(couplingCutoffMetres >= 0) || !Double.isFinite(couplingCutoffMetres))
                throw new IllegalArgumentException(
                    "couplingCutoffMetres must be a finite value ≥ 0, got " + couplingCutoffMetres);
        }

        /** True when no NV–NV coupling can form — every NV stays an independent singleton. */
        @JsonIgnore
        public boolean isIndependent() {
            return maxClusterSize <= 1 || couplingCutoffMetres <= 0;
        }
    }

    /** The fully-independent classical model (no NV–NV coupling) — the default. */
    static NvSimulationMethod independent() {
        return new ClusteredQubitHamiltonian(1, 0.0);
    }
}
