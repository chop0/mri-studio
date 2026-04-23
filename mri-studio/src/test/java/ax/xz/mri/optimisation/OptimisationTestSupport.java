package ax.xz.mri.optimisation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.optimisation.ProblemGeometry.DynamicFieldSamples;
import ax.xz.mri.optimisation.ProblemGeometry.ReceiveCoilSamples;
import ax.xz.mri.support.TestBlochDataFactory;

import java.util.List;

public final class OptimisationTestSupport {
    private OptimisationTestSupport() {}

    public static BlochData sampleDocument() {
        return TestBlochDataFactory.sampleDocument();
    }

    public static List<PulseSegment> pulseA() {
        return TestBlochDataFactory.pulseA();
    }

    public static List<PulseSegment> pulseB() {
        return TestBlochDataFactory.pulseB();
    }

    /**
     * Finite-train template with per-step control layout = 4 channels + rfGate = 5 scalars.
     */
    public static SequenceTemplate finiteTemplateFor(List<PulseSegment> pulse) {
        return SequenceTemplate.finiteTrain(pulse.stream()
            .map(segment -> new ControlSegmentSpec(1e-6, segment.steps().size(), 0, 5))
            .toList());
    }

    /**
     * Single-point geometry for simple objective tests. Uses a uniform isotropic receive
     * coil ({@code Ex = rxWeight}, {@code Ey = 0}) so signal reduces to {@code rxWeight · Mx}.
     */
    public static ProblemGeometry singlePointGeometry(double rxWeight, double outWeight) {
        var rf = new DynamicFieldSamples("RF", 0, 2, 0.0,
            new double[]{1.0}, new double[]{0.0}, new double[]{0.0});
        var gx = new DynamicFieldSamples("Gx", 2, 1, 0.0,
            new double[]{0.0}, new double[]{0.0}, new double[]{0.0});
        var gz = new DynamicFieldSamples("Gz", 3, 1, 0.0,
            new double[]{0.0}, new double[]{0.0}, new double[]{0.0});
        var primary = new ReceiveCoilSamples(
            "Primary RX", 1.0, 0.0,
            new double[]{rxWeight}, new double[]{0.0});
        return new ProblemGeometry(
            new double[]{0.0},
            new double[]{0.0},
            new double[]{1.0},
            new double[]{0.0},
            new double[]{outWeight},
            List.of(rf, gx, gz),
            primary,
            1.0,
            1.0,
            1.0,
            1, 1
        );
    }

    public static OptimisationProblem simpleProblem(SequenceTemplate template, ObjectiveSpec objectiveSpec) {
        return new OptimisationProblem(singlePointGeometry(1.0, 0.0), template, objectiveSpec);
    }

    public static ObjectiveSpec simpleObjective(ObjectiveMode mode) {
        return new ObjectiveSpec(mode, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
