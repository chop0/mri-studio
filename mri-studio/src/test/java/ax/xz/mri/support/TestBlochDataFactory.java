package ax.xz.mri.support;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.scenario.Scenario;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;

import java.util.List;
import java.util.Map;

/** Small deterministic Bloch documents used by unit tests. */
public final class TestBlochDataFactory {
    private TestBlochDataFactory() {
    }

    public static BlochData sampleDocument() {
        return new BlochData(
            sampleField(),
            List.of(
                new BlochData.IsochromatDef("Centre", "#00aa55", true),
                new BlochData.IsochromatDef("Edge", "#2266dd", true)
            ),
            Map.of(
                "Full GRAPE", new Scenario(Map.of(
                    "0", pulseB(),
                    "3", pulseA()
                )),
                "Baseline", new Scenario(Map.of(
                    "10", pulseB(),
                    "2", pulseA(),
                    "1", pulseA()
                ))
            )
        );
    }

    public static BlochData brokenDocumentMissingSegments() {
        var field = sampleField();
        field.segments = null;
        return new BlochData(
            field,
            List.of(new BlochData.IsochromatDef("Broken", "#aa0000", true)),
            Map.of("Broken", new Scenario(Map.of("0", pulseA())))
        );
    }

    public static List<PulseSegment> pulseA() {
        return List.of(
            new PulseSegment(List.of(
                new PulseStep(1.0e-6, 0, 0, 0, 1.0),
                new PulseStep(1.0e-6, 0, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                new PulseStep(0, 0, 0, 0.010, 0),
                new PulseStep(0, 0, 0, -0.010, 0)
            ))
        );
    }

    public static List<PulseSegment> pulseB() {
        return List.of(
            new PulseSegment(List.of(
                new PulseStep(2.0e-6, 0, 0, 0, 1.0),
                new PulseStep(0.5e-6, 0.5e-6, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                new PulseStep(0, 0, 0.008, 0.018, 0),
                new PulseStep(0, 0, -0.008, -0.018, 0)
            ))
        );
    }

    private static FieldMap sampleField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0n = 1.5;
        field.dBzUt = new double[][]{
            {0.0, 4.0, 1.5},
            {1.0, -2.0, 0.5},
            {2.5, -1.0, 0.0}
        };
        field.mx0 = null;
        field.my0 = null;
        field.mz0 = null;
        field.fovX = 0.04;
        field.fovZ = 0.04;
        field.gamma = 267.5e6;
        field.t1 = 1.0;
        field.t2 = 0.08;
        field.sliceHalf = 0.005;
        field.segments = List.of(
            new Segment(1.0e-6, 0, 2),
            new Segment(1.0e-6, 2, 0)
        );
        return field;
    }
}
