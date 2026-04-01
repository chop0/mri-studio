package ax.xz.mri.optimisation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds optimisation requests from existing viewer documents and seed pulses. */
public final class ScenarioBuilder {
    public record BuildOptions(
        String seedScenarioName,
        String iterationKey,
        String outputScenarioName,
        ObjectiveMode objectiveMode,
        int prefixSegmentCount,
        FreeMaskMode freeMaskMode,
        int totalIterations,
        int snapshotEvery,
        int radialStride,
        int axialStride
    ) {
    }

    public OptimisationRequest buildRequest(BlochData data, BuildOptions options) {
        var scenario = data.scenarios().get(options.seedScenarioName());
        if (scenario == null) throw new IllegalArgumentException("Unknown scenario: " + options.seedScenarioName());
        String iterationKey = options.iterationKey() == null
            ? scenario.iterationKeys().get(scenario.iterationKeys().size() - 1)
            : options.iterationKey();
        var pulse = scenario.pulses().get(iterationKey);
        if (pulse == null) throw new IllegalArgumentException("Unknown iteration: " + iterationKey);

        var fullSpecs = data.field().segments.stream()
            .map(segment -> new ControlSegmentSpec(segment.dt(), segment.nFree(), segment.nPulse(), 5))
            .toList();
        List<PulseSegment> optimisedSegments = PulseParameterCodec.copySegments(pulse);
        SequenceTemplate template;
        ControlParameterisation parameterisation;

        if (options.objectiveMode() == ObjectiveMode.PERIODIC_CYCLE) {
            if (options.prefixSegmentCount() < 0 || options.prefixSegmentCount() >= pulse.size()) {
                throw new IllegalArgumentException("prefixSegmentCount must leave at least one repeated segment");
            }
            var reducedSpecs = new ArrayList<ControlSegmentSpec>();
            reducedSpecs.addAll(fullSpecs.subList(0, options.prefixSegmentCount()));
            reducedSpecs.add(fullSpecs.get(options.prefixSegmentCount()));
            template = SequenceTemplate.periodicCycle(reducedSpecs, options.prefixSegmentCount(), 1, pulse.size() - options.prefixSegmentCount());
            optimisedSegments = new ArrayList<>(PulseParameterCodec.copySegments(pulse.subList(0, options.prefixSegmentCount() + 1)));
            parameterisation = new PeriodicCycleParameterisation(template);
        } else {
            template = SequenceTemplate.finiteTrain(fullSpecs);
            parameterisation = new IdentityControlParameterisation(template);
        }

        var geometry = CpuObjectiveEngine.geometryFromFieldMap(data.field(), options.radialStride(), options.axialStride());
        var objective = new ObjectiveSpec(
            options.objectiveMode(),
            12.0,
            1.5,
            options.objectiveMode() == ObjectiveMode.FULL_TRAIN ? 0.001 : 0.0,
            0.1,
            1.5,
            0.15,
            1.0
        );
        var problem = new OptimisationProblem(geometry, template, objective);
        return new OptimisationRequest(
            options.seedScenarioName(),
            options.outputScenarioName(),
            problem,
            optimisedSegments,
            parameterisation,
            PulseParameterCodec.defaultFreeMask(template, options.freeMaskMode()),
            PulseParameterCodec.defaultLowerBounds(template),
            PulseParameterCodec.defaultUpperBounds(template),
            ContinuationSchedule.defaultForIterations(options.totalIterations()),
            options.snapshotEvery(),
            new AtomicBoolean(false)
        );
    }
}
