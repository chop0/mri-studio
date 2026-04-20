package ax.xz.mri.optimisation;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.optimisation.ProblemGeometry.DynamicFieldSamples;
import ax.xz.mri.support.TestBlochDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OptimisationTestSupport {
    private OptimisationTestSupport() {
    }

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
     * Matches the legacy layout used in test fixtures.
     */
    public static SequenceTemplate finiteTemplateFor(List<PulseSegment> pulse) {
        return SequenceTemplate.finiteTrain(pulse.stream()
            .map(segment -> new ControlSegmentSpec(1e-6, segment.steps().size(), 0, 5))
            .toList());
    }

    /**
     * Single-point geometry for simple objective tests. Exposes one QUADRATURE field (RF)
     * and two REAL fields (Gx, Gz) with flat per-point samples so penalties are well-defined.
     */
    public static ProblemGeometry singlePointGeometry(double inWeight, double outWeight, double sMax) {
        var rf = new DynamicFieldSamples("RF", 0, 2, 0.0,
            new double[]{1.0}, new double[]{0.0}, new double[]{0.0});
        var gx = new DynamicFieldSamples("Gx", 2, 1, 0.0,
            new double[]{0.0}, new double[]{0.0}, new double[]{0.0});
        var gz = new DynamicFieldSamples("Gz", 3, 1, 0.0,
            new double[]{0.0}, new double[]{0.0}, new double[]{0.0});
        return new ProblemGeometry(
            new double[]{0.0},            // mx0
            new double[]{0.0},            // my0
            new double[]{1.0},            // mz0
            new double[]{0.0},            // staticBz (off-resonance)
            new double[]{inWeight},
            new double[]{outWeight},
            List.of(rf, gx, gz),
            sMax,
            1.0,  // gamma
            1.0,  // t1
            1.0,  // t2
            1, 1  // nr, nz
        );
    }

    public static OptimisationProblem simpleProblem(SequenceTemplate template, ObjectiveSpec objectiveSpec) {
        return new OptimisationProblem(singlePointGeometry(1.0, 0.0, 1.0), template, objectiveSpec);
    }

    public static ObjectiveSpec simpleObjective(ObjectiveMode mode) {
        return new ObjectiveSpec(mode, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Write a runtime {@link BlochData} back out in the legacy on-disk JSON
     * format. {@link ax.xz.mri.service.io.BlochDataReader} only knows the
     * legacy field schema (B0n, dBz_uT, etc.), so this adapter rebuilds that
     * shape from the general-form {@link ax.xz.mri.model.field.FieldMap}.
     * Dynamic-field spatial shapes are discarded — the adapter on the read
     * side reconstructs synthetic legacy channels from b0Ref and staticBz.
     */
    public static void writeBlochDataJson(File file, BlochData source) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        var f = source.field();
        var legacyField = new LinkedHashMap<String, Object>();
        legacyField.put("r_mm", f.rMm);
        legacyField.put("z_mm", f.zMm);
        legacyField.put("B0n", f.b0Ref);
        int nR = f.rMm != null ? f.rMm.length : 0;
        int nZ = f.zMm != null ? f.zMm.length : 0;
        double[][] dBzUt = new double[nR][nZ];
        if (f.staticBz != null) {
            for (int r = 0; r < nR; r++) for (int z = 0; z < nZ; z++) {
                dBzUt[r][z] = f.staticBz[r][z] * 1e6; // T → μT
            }
        }
        legacyField.put("dBz_uT", dBzUt);
        if (f.mx0 != null) legacyField.put("Mx0", f.mx0);
        if (f.my0 != null) legacyField.put("My0", f.my0);
        if (f.mz0 != null) legacyField.put("Mz0", f.mz0);
        legacyField.put("FOV_X", f.fovX);
        legacyField.put("FOV_Z", f.fovZ);
        legacyField.put("gamma", f.gamma);
        legacyField.put("T1", f.t1);
        legacyField.put("T2", f.t2);
        if (f.sliceHalf != null) legacyField.put("slice_half", f.sliceHalf);
        legacyField.put("segments", f.segments);
        root.put("field", legacyField);
        root.put("iso", source.iso().stream()
            .map(iso -> List.of(iso.name(), iso.colour(), iso.inSlice()))
            .map(values -> (Object) values)
            .toList());
        var scenarios = new LinkedHashMap<String, Object>();
        for (var entry : source.scenarios().entrySet()) {
            var scenario = new LinkedHashMap<String, Object>();
            scenario.put("pulses", encodePulses(entry.getValue().pulses()));
            scenarios.put(entry.getKey(), scenario);
        }
        root.put("scenarios", scenarios);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file, root);
    }

    private static Map<String, Object> encodePulses(Map<String, List<PulseSegment>> pulses) {
        var encoded = new LinkedHashMap<String, Object>();
        for (var entry : pulses.entrySet()) {
            encoded.put(entry.getKey(), encodePulse(entry.getValue()));
        }
        return encoded;
    }

    private static List<Object> encodePulse(List<PulseSegment> segments) {
        return segments.stream()
            .map(segment -> segment.steps().stream()
                .map(OptimisationTestSupport::encodeStep)
                .toList())
            .map(values -> (Object) values)
            .toList();
    }

    private static List<Object> encodeStep(PulseStep step) {
        var out = new java.util.ArrayList<Object>(step.channelCount() + 1);
        for (var v : step.controls()) out.add(v);
        out.add(step.rfGate());
        return out;
    }
}
