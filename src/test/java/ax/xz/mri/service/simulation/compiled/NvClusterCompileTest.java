package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NV-NV interaction tiering verification — the cluster-tier framework is the
 * general case, with independent-NV simulation as the cluster-size-1 special
 * case. Pairs of NVs whose Euclidean separation is below
 * {@link NvEnsemble#interactionThresholdMetres} get grouped into one cluster
 * by the compile-time union-find; everything else stays independent.
 */
final class NvClusterCompileTest {

    @Test
    void zeroThresholdProducesIndependentSingletonClusters() {
        var ensemble = ensembleWith(
            List.of(
                new NvCentre(0, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(10e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(20e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)
            ),
            /*interactionThreshold*/ 0.0);
        var sim = compile(ensemble);
        var kernel = (NvKernel) sim.compiledSubstance(0);
        var clusters = kernel.clusters();
        assertEquals(3, clusters.length, "Zero threshold: every NV is its own cluster");
        for (int[] c : clusters) {
            assertEquals(1, c.length, "Singletons should have size 1");
        }
    }

    @Test
    void closePairsAreMergedIntoOneCluster() {
        // Three NVs: A and B sit 30 nm apart (close), C sits 200 nm away (far).
        // With threshold 50 nm, A+B should cluster, C stays independent.
        var ensemble = ensembleWith(
            List.of(
                new NvCentre(0,     0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(30e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(200e-9, 0, 50e-9, NvAxis.AXIS_PLUS_Z)
            ),
            /*interactionThreshold*/ 50e-9);
        var sim = compile(ensemble);
        var kernel = (NvKernel) sim.compiledSubstance(0);
        var clusters = kernel.clusters();
        assertEquals(2, clusters.length, "Threshold 50 nm: A+B → one cluster, C → separate");
        // Find the size-2 cluster and the size-1 cluster.
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
        // Four NVs in a chain: A-B, B-C, C-D each 30 nm apart, threshold 50 nm.
        // Union-find should transitively merge all four into one cluster.
        var ensemble = ensembleWith(
            List.of(
                new NvCentre(0,      0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(30e-9,  0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(60e-9,  0, 50e-9, NvAxis.AXIS_PLUS_Z),
                new NvCentre(90e-9,  0, 50e-9, NvAxis.AXIS_PLUS_Z)
            ),
            50e-9);
        var sim = compile(ensemble);
        var kernel = (NvKernel) sim.compiledSubstance(0);
        var clusters = kernel.clusters();
        assertEquals(1, clusters.length, "Chain of close pairs: all four merge into one cluster");
        assertEquals(4, clusters[0].length, "Cluster contains all four NVs");
    }

    private static NvEnsemble ensembleWith(List<NvCentre> centres, double threshold) {
        // Use CUSTOM geometry so the NvEnsemble materialises exactly the centres
        // the test fed it — lengthMetres / depthMetres are required by the
        // record but irrelevant for CUSTOM (centres come from customCentres()).
        var geom = new NvArrayGeometry(NvArrayShape.CUSTOM, centres.size(), 1e-6, 50e-9,
            NvAxis.AXIS_PLUS_Z, 0L, centres);
        return new NvEnsemble(geom, NvPhysics.defaults(), 0L, threshold);
    }

    private static CompiledSimulation compile(NvEnsemble ensemble) {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "C",
            List.<CircuitComponent>of(), List.of(), CircuitLayout.empty());
        List<Substance> substances = List.of(ensemble);
        return CompiledSimulation.compile(new CompiledSimulation.CompileRequest(doc, ax.xz.mri.state.ProjectState.empty(),
            substances, List.<Segment>of(), List.<PulseSegment>of(), 0.0));
    }
}
