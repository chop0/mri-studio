package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.substance.NvEnsemble;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile-time NV–NV interaction tiering via union-find.
 *
 * <p>Pairs of NV centres whose Euclidean distance is below
 * {@link NvEnsemble#interactionThresholdMetres} are merged into the same
 * cluster. Each resulting cluster is simulated as a joint state of
 * dimension up to {@code 3ᵏ} (well-conditioned for {@code k ≤ ~6}) with a
 * sparse Hamiltonian over within-cluster couplings. Singletons —
 * isolated NVs — are simulated independently (the {@code k = 1} special
 * case of the general framework, no separate code path).
 *
 * <p>The union-find here is the same pattern the circuit's wire-graph
 * resolver uses to build MNA nets, applied to a different equivalence
 * relation. {@code interactionThresholdMetres = 0} disables interactions
 * entirely; every NV is its own cluster.
 */
final class NvClusterUnion {
    private NvClusterUnion() {}

    static int[][] union(NvEnsemble ensemble) {
        var centres = ensemble.centres();
        int n = centres.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double thresh = ensemble.interactionThresholdMetres();
        if (thresh > 0) {
            double th2 = thresh * thresh;
            for (int i = 0; i < n; i++) {
                var a = centres.get(i);
                for (int j = i + 1; j < n; j++) {
                    var b = centres.get(j);
                    double dx = a.xMetres() - b.xMetres();
                    double dy = a.yMetres() - b.yMetres();
                    double dz = a.zMetres() - b.zMetres();
                    if (dx * dx + dy * dy + dz * dz <= th2) merge(parent, i, j);
                }
            }
        }
        var buckets = new java.util.HashMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            buckets.computeIfAbsent(r, k -> new ArrayList<>()).add(i);
        }
        int[][] out = new int[buckets.size()][];
        int k = 0;
        for (var bucket : buckets.values()) {
            int[] arr = new int[bucket.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = bucket.get(i);
            out[k++] = arr;
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void merge(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
