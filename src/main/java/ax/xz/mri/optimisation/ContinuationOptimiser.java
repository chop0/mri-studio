package ax.xz.mri.optimisation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** Runs continuation stages while preserving snapshot numbering across stages. */
public final class ContinuationOptimiser {
    public interface StageRunner {
        LbfgsbSolver.SolverResult run(ContinuationStage stage, double[] x0, StageListener listener);
    }

    public interface StageListener {
        boolean onIteration(int stageIteration, double[] x, double value);
    }

    public record Result(
        double[] bestX,
        double bestValue,
        int iterations,
        Map<Integer, double[]> snapshots,
        boolean success,
        String message
    ) {
        public Result {
            var sorted = new TreeMap<Integer, double[]>();
            snapshots.forEach((iteration, vector) -> sorted.put(iteration, vector.clone()));
            var ordered = new LinkedHashMap<Integer, double[]>();
            sorted.forEach(ordered::put);
            snapshots = Collections.unmodifiableMap(ordered);
            bestX = bestX.clone();
        }
    }

    public Result run(
        ContinuationSchedule schedule,
        double[] initialX,
        int snapshotEvery,
        StageRunner stageRunner
    ) {
        var snapshots = new LinkedHashMap<Integer, double[]>();
        snapshots.put(0, initialX.clone());
        double[] current = initialX.clone();
        final double[][] bestXHolder = {initialX.clone()};
        final double[] bestValueHolder = {Double.POSITIVE_INFINITY};
        int iterationOffset = 0;
        boolean success = true;
        String message = "Completed continuation";

        for (var stage : schedule.stages()) {
            final int stageIterationOffset = iterationOffset;
            var stageResult = stageRunner.run(stage, current, (stageIteration, x, value) -> {
                int globalIteration = stageIterationOffset + stageIteration;
                if (globalIteration % snapshotEvery == 0) {
                    snapshots.put(globalIteration, x.clone());
                }
                if (value < bestValueHolder[0]) {
                    bestValueHolder[0] = value;
                    bestXHolder[0] = x.clone();
                }
                return false;
            });
            iterationOffset += stageResult.iterations();
            current = stageResult.x().clone();
            snapshots.put(iterationOffset, current.clone());
            if (stageResult.value() < bestValueHolder[0]) {
                bestValueHolder[0] = stageResult.value();
                bestXHolder[0] = current.clone();
            }
            success &= stageResult.success();
            message = stageResult.message();
            if (!stageResult.success()) break;
        }
        return new Result(bestXHolder[0], bestValueHolder[0], iterationOffset, snapshots, success, message);
    }
}
