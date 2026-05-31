package ax.xz.mri.service.simulation.compiled;

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
import ax.xz.mri.model.simulation.NvSimulationMethod;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NV–NV interaction tiering verification — the cluster-tier framework is the
 * general case, with independent-NV simulation as the cluster-size-1 special
 * case. How centres group is a property of the <em>simulation method</em>
 * ({@link NvSimulationMethod.ClusteredQubitHamiltonian}) on the simulation
 * config, not of the substance: pairs of NVs closer than the method's coupling
 * cutoff get merged by the compile-time union-find; anything beyond stays
 * independent; a cluster exceeding the method's max-cluster-size is
 * deterministically sub-split.
 */
final class NvClusterCompileTest {

    @Test
    void independentMethodProducesSingletonClusters() {
        var ensemble = ensembleWith(List.of(
            new NvCentre(0, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(10e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(20e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)));
        var kernel = clustersOf(ensemble, NvSimulationMethod.independent());
        var clusters = kernel.clusters();
        assertEquals(3, clusters.length, "Independent method: every NV is its own cluster");
        for (int[] c : clusters) assertEquals(1, c.length, "Singletons should have size 1");
    }

    @Test
    void closePairsAreMergedIntoOneCluster() {
        // A and B sit 30 nm apart (close), C sits 200 nm away (far). With a
        // 50 nm cutoff, A+B should cluster while C stays independent.
        var ensemble = ensembleWith(List.of(
            new NvCentre(0,      0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(30e-9,  0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(200e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)));
        var kernel = clustersOf(ensemble, clustered(8, 50e-9));
        var clusters = kernel.clusters();
        assertEquals(2, clusters.length, "Cutoff 50 nm: A+B → one cluster, C → separate");
        int size2 = 0, size1 = 0;
        for (int[] c : clusters) {
            if (c.length == 2) size2++;
            if (c.length == 1) size1++;
        }
        assertEquals(1, size2, "Exactly one size-2 cluster");
        assertEquals(1, size1, "Exactly one size-1 cluster");
    }

    @Test
    void chainOfClosePairsAllJoinTransitively() {
        // Four NVs in a chain: A-B, B-C, C-D each 30 nm apart, cutoff 50 nm.
        // Union-find should transitively merge all four into one cluster when
        // the max cluster size admits it.
        var ensemble = chainOfFour();
        var kernel = clustersOf(ensemble, clustered(8, 50e-9));
        var clusters = kernel.clusters();
        assertEquals(1, clusters.length, "Chain of close pairs: all four merge into one cluster");
        assertEquals(4, clusters[0].length, "Cluster contains all four NVs");
    }

    @Test
    void oversizedClusterSubSplitsToCap() {
        // Same transitive chain of four, but the method caps clusters at 2.
        // The size-4 bucket must sub-split into blocks that each respect the
        // cap — deterministically, by widest-axis bisection.
        var ensemble = chainOfFour();
        var kernel = clustersOf(ensemble, clustered(2, 50e-9));
        var clusters = kernel.clusters();
        for (int[] c : clusters) assertTrue(c.length <= 2, "No block may exceed the cap");
        int total = 0;
        for (int[] c : clusters) total += c.length;
        assertEquals(4, total, "Sub-split partitions every centre exactly once");
        // Widest-axis bisection of [0,30,60,90] nm → {0,30} | {60,90}.
        assertEquals(2, clusters.length, "Cap 2 splits the chain of four into two pairs");
    }

    @Test
    void nvDiamondTemplateFormsExactlyOneDimerAtDefaultCutoff() {
        // The shipped NV-diamond template: a sparse 16-NV line plus one close
        // partner. Under the template's default method (cap 3, 30 nm cutoff —
        // see NvDiamondConfigStep) exactly that dimer couples; the rest stay
        // independent. This proves the default template demonstrates NV–NV
        // coupling out of the box without an O(N²) blow-up.
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "demo");
        var nv = (NvEnsemble) built.newSubstances().get(0).substance();
        int[][] clusters = NvClusterUnion.union(nv.centres(), 30e-9, 3);
        int pairs = 0, singles = 0;
        for (int[] c : clusters) {
            assertTrue(c.length <= 3, "Default cap is 3 — no block may exceed it");
            if (c.length == 2) pairs++;
            else if (c.length == 1) singles++;
        }
        assertEquals(1, pairs, "Template couples exactly one dipolar dimer by default");
        assertEquals(15, singles, "The 15 sparse-line NVs away from the dimer stay independent");
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static NvSimulationMethod clustered(int maxClusterSize, double cutoffMetres) {
        return new NvSimulationMethod.ClusteredQubitHamiltonian(maxClusterSize, cutoffMetres);
    }

    private static NvEnsemble chainOfFour() {
        return ensembleWith(List.of(
            new NvCentre(0,     0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(30e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(60e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
            new NvCentre(90e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)));
    }

    private static NvEnsemble ensembleWith(List<NvCentre> centres) {
        // CUSTOM geometry materialises exactly the centres the test fed it —
        // lengthMetres / depthMetres are required by the record but unused for
        // CUSTOM (centres come from customCentres()).
        var geom = new NvArrayGeometry(NvArrayShape.CUSTOM, centres.size(), 1e-6, 50e-9,
            NvAxis.AXIS_PLUS_Z, 0L, centres);
        return new NvEnsemble(geom, NvPhysics.defaults(), 0L);
    }

    private static NvKernel clustersOf(NvEnsemble ensemble, NvSimulationMethod method) {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        List<Substance> substances = List.of(ensemble);
        var sim = CompiledSimulation.compile(new CompiledSimulation.CompileRequest(
            doc, ProjectState.empty(), substances,
            List.<Segment>of(), List.<PulseSegment>of(), 0.0, method));
        return (NvKernel) sim.compiledSubstance(0);
    }
}
