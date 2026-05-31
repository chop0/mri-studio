package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.nv.NvCentre;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compile-time NV–NV interaction tiering via union-find.
 *
 * <p>Pairs of NV centres closer than {@code cutoffMetres} are merged into the
 * same cluster (the same union-find pattern the circuit's wire-graph resolver
 * uses for MNA nets, on a distance equivalence). Each cluster is then evolved
 * jointly by {@link NvClusterEngine} as a {@code 2^k} density matrix carrying
 * the within-cluster dipolar coupling. Singletons (isolated NVs, or any NV
 * when {@code cutoffMetres ≤ 0}) are the {@code k = 1} case — the cheap
 * classical Bloch path.
 *
 * <p>The clustering parameters are properties of the <em>simulation method</em>
 * ({@link ax.xz.mri.model.simulation.NvSimulationMethod}), not of the
 * substance: {@code cutoffMetres} (which pairs couple) and {@code maxClusterSize}
 * (the joint-dimension cap). A cluster that exceeds the cap is deterministically
 * sub-split by widest-spread spatial bisection — dropping the (weak, longer-
 * range) coupling between sub-blocks — and a warning is logged. No silent
 * truncation.
 *
 * <p>Output is deterministic: clusters are ordered by their smallest member
 * index and each cluster's members are ascending, so snapshots/restore and
 * repeated compiles are stable.
 */
final class NvClusterUnion {
    private NvClusterUnion() {}

    private static final Logger LOG = System.getLogger(NvClusterUnion.class.getName());

    static int[][] union(List<NvCentre> centres, double cutoffMetres, int maxClusterSize) {
        int n = centres.size();
        int cap = Math.max(1, maxClusterSize);
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        if (cutoffMetres > 0) {
            double th2 = cutoffMetres * cutoffMetres;
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

        // Bucket by root, ascending members.
        var buckets = new java.util.TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            buckets.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).add(i);
        }

        var out = new ArrayList<int[]>();
        for (var bucket : buckets.values()) {
            if (bucket.size() <= cap) {
                out.add(toIntArray(bucket));
            } else {
                int before = out.size();
                subSplit(bucket, centres, cap, out);
                LOG.log(Level.WARNING,
                    "NV cluster of {0} centres exceeds max cluster size {1}; sub-split into {2} blocks — "
                    + "inter-block dipolar coupling dropped.",
                    bucket.size(), cap, out.size() - before);
            }
        }
        // Order clusters by smallest member for a stable layout.
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out.toArray(new int[0][]);
    }

    /** Recursively bisect {@code members} along the widest-spread axis until each block ≤ cap. */
    private static void subSplit(List<Integer> members, List<NvCentre> centres, int cap, List<int[]> out) {
        if (members.size() <= cap) {
            var sorted = new ArrayList<>(members);
            sorted.sort(Comparator.naturalOrder());
            out.add(toIntArray(sorted));
            return;
        }
        int axis = widestAxis(members, centres);
        var sorted = new ArrayList<>(members);
        sorted.sort(Comparator
            .comparingDouble((Integer i) -> coord(centres.get(i), axis))
            .thenComparingInt(i -> i));
        int mid = sorted.size() / 2;
        subSplit(sorted.subList(0, mid), centres, cap, out);
        subSplit(sorted.subList(mid, sorted.size()), centres, cap, out);
    }

    private static int widestAxis(List<Integer> members, List<NvCentre> centres) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int idx : members) {
            var c = centres.get(idx);
            for (int ax = 0; ax < 3; ax++) {
                double v = coord(c, ax);
                lo[ax] = Math.min(lo[ax], v);
                hi[ax] = Math.max(hi[ax], v);
            }
        }
        int best = 0;
        double bestSpread = hi[0] - lo[0];
        for (int ax = 1; ax < 3; ax++) {
            double spread = hi[ax] - lo[ax];
            if (spread > bestSpread) { bestSpread = spread; best = ax; }
        }
        return best;
    }

    private static double coord(NvCentre c, int axis) {
        return switch (axis) {
            case 0 -> c.xMetres();
            case 1 -> c.yMetres();
            default -> c.zMetres();
        };
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
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
