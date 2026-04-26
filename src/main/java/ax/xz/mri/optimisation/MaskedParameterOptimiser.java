package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.LinkedHashMap;
import java.util.List;

/** Java optimiser backend using masked controls, continuation, and L-BFGS-B. */
public final class MaskedParameterOptimiser implements OptimiserBackend {
    private final LbfgsbSolver solver;
    private final ContinuationOptimiser continuation = new ContinuationOptimiser();

    public MaskedParameterOptimiser() {
        this(new LbfgsbSolver());
    }

    public MaskedParameterOptimiser(LbfgsbSolver solver) {
        this.solver = solver;
    }

    @Override
    public OptimisationResult optimise(OptimisationRequest request, ObjectiveEngine engine) {
        var template = request.problem().sequenceTemplate();
        double[] initialFull = PulseParameterCodec.flatten(request.initialSegments());
        var mask = MaskedVector.of(request.freeMask());

        var snapshots = new LinkedHashMap<Integer, List<PulseSegment>>();
        snapshots.put(0, request.parameterisation().expandSegments(request.initialSegments()));

        if (mask.size() == 0) {
            return new OptimisationResult(
                request.initialSegments(),
                snapshots.get(0),
                new SnapshotSeries(snapshots),
                engine.simulateSignal(request.problem(), request.initialSegments()),
                0,
                0,
                engine.evaluate(request.problem(), request.initialSegments()).value(),
                true,
                "No free variables"
            );
        }

        double[] lowerFree = mask.pick(request.lowerBounds());
        double[] upperFree = mask.pick(request.upperBounds());
        int[] evaluations = {0};

        var result = continuation.run(
            request.continuationSchedule(),
            mask.pick(initialFull),
            request.snapshotEvery(),
            (stage, x0, listener) -> {
                var stageProblem = request.problem().withObjectiveSpec(
                    request.problem().objectiveSpec().withRfSmoothMultiplier(stage.rfSmoothMultiplier())
                );
                return solver.solve(
                    xFree -> {
                        var segments = expand(mask, initialFull, xFree, template);
                        double value = engine.evaluate(stageProblem, segments).value();
                        double[] gradient = engine.gradient(stageProblem, segments);
                        evaluations[0]++;
                        return new LbfgsbSolver.ValueGradient(value, mask.pick(gradient));
                    },
                    x0, lowerFree, upperFree, stage.iterations(),
                    (iteration, x, value) -> {
                        if (request.stopRequested().get()) return true;
                        listener.onIteration(iteration, x, value);
                        return false;
                    }
                );
            }
        );

        // Fan out double[] snapshots → List<PulseSegment> snapshots.
        result.snapshots().forEach((iteration, x) -> {
            if (iteration == 0) return;
            snapshots.put(iteration, request.parameterisation().expandSegments(expand(mask, initialFull, x, template)));
        });

        var bestSegments = expand(mask, initialFull, result.bestX(), template);
        return new OptimisationResult(
            bestSegments,
            request.parameterisation().expandSegments(bestSegments),
            new SnapshotSeries(snapshots),
            engine.simulateSignal(request.problem(), bestSegments),
            result.iterations(),
            evaluations[0],
            result.bestValue(),
            result.success(),
            result.message()
        );
    }

    private static List<PulseSegment> expand(MaskedVector mask, double[] initialFull, double[] xFree, SequenceTemplate template) {
        return PulseParameterCodec.split(mask.merge(initialFull, xFree), template);
    }
}
