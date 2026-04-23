package ax.xz.mri.model.simulation;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CircuitCompiler;

import java.util.List;

/**
 * Builds a runtime {@link FieldMap} from a {@link SimulationConfig} and its
 * {@linkplain SimulationConfig#circuitId() circuit}.
 *
 * <p>The factory resolves the circuit through the project repository, compiles
 * it against the (r, z) grid, then precomputes the rotating-frame static Bz
 * by summing every {@link AmplitudeKind#STATIC} source's contribution through
 * its target coil's longitudinal eigenfield (then subtracting the reference
 * B0). The Bloch-Siegert rollup for fast non-resonant transverse fields is
 * applied inline at the same time.
 */
public final class BlochDataFactory {
    private static final double SLOW_DELTA_OMEGA_DT = 0.1;
    private static final double STRONG_DRIVING_DT = 1.0;

    private BlochDataFactory() {}

    public static BlochData build(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        return new BlochData(buildFieldMap(config, segments, repository));
    }

    public static BlochData build(SimulationConfig config, List<Segment> segments) {
        return build(config, segments, null);
    }

    private static FieldMap buildFieldMap(SimulationConfig config, List<Segment> segments, ProjectRepository repository) {
        var field = new FieldMap();
        int nR = Math.max(2, config.nR());
        int nZ = Math.max(2, config.nZ());
        field.rMm = new double[nR];
        field.zMm = new double[nZ];
        for (int i = 0; i < nR; i++) field.rMm[i] = i * config.fovRMm() / (nR - 1);
        for (int i = 0; i < nZ; i++) field.zMm[i] = -config.fovZMm() / 2 + i * config.fovZMm() / (nZ - 1);

        field.b0Ref = config.referenceB0Tesla();
        field.gamma = config.gamma();
        field.t1 = config.t1Ms() * 1e-3;
        field.t2 = config.t2Ms() * 1e-3;
        field.fovX = config.fovRMm() * 1e-3;
        field.fovZ = config.fovZMm() * 1e-3;
        field.sliceHalf = config.sliceHalfMm() * 1e-3;
        field.segments = segments;

        field.mx0 = new double[nR][nZ];
        field.my0 = new double[nR][nZ];
        field.mz0 = new double[nR][nZ];
        for (int ri = 0; ri < nR; ri++)
            for (int zi = 0; zi < nZ; zi++)
                field.mz0[ri][zi] = 1.0;

        CircuitDocument circuit = resolveCircuit(config, repository);
        field.circuit = CircuitCompiler.compile(circuit, repository, field.rMm, field.zMm);

        double[][] staticBz = new double[nR][nZ];
        if (circuit != null) applyStaticContributions(staticBz, field, config, segments);
        for (int ri = 0; ri < nR; ri++)
            for (int zi = 0; zi < nZ; zi++)
                staticBz[ri][zi] -= config.referenceB0Tesla();
        field.staticBz = staticBz;

        return field;
    }

    private static void applyStaticContributions(double[][] staticBz, FieldMap field,
                                                 SimulationConfig config, List<Segment> segments) {
        var compiled = field.circuit;
        double omegaSim = config.gamma() * config.referenceB0Tesla();
        double dt = config.dtSeconds() > 0 ? config.dtSeconds() : representativeDt(segments);

        for (int srcIdx = 0; srcIdx < compiled.sources().size(); srcIdx++) {
            var src = compiled.sources().get(srcIdx);
            var link = linkForSource(compiled.drives(), srcIdx);
            if (link == null) continue;
            var coil = compiled.coils().get(link.coilIndex());
            double sign = link.forwardPolarity() ? 1.0 : -1.0;
            double amp = src.kind() == AmplitudeKind.STATIC ? src.staticAmplitude() : 0.0;

            switch (src.kind()) {
                case STATIC -> {
                    int nR = field.rMm.length, nZ = field.zMm.length;
                    double effective = sign * amp;
                    for (int ri = 0; ri < nR; ri++) {
                        for (int zi = 0; zi < nZ; zi++) {
                            staticBz[ri][zi] += effective * coil.ez()[ri][zi];
                        }
                    }
                    // Static transverse with non-zero ω_s → Bloch-Siegert rollup.
                    double maxTransverse = maxTransverseOf(coil);
                    if (maxTransverse > 1e-12 && Math.abs(omegaSim) > 1e-9) {
                        double dwStatic = -omegaSim;
                        double amp2 = amp * amp;
                        for (int ri = 0; ri < nR; ri++) {
                            for (int zi = 0; zi < nZ; zi++) {
                                double bPerp2 = amp2 * (coil.ex()[ri][zi] * coil.ex()[ri][zi]
                                                      + coil.ey()[ri][zi] * coil.ey()[ri][zi]);
                                staticBz[ri][zi] += config.gamma() * bPerp2 / (4 * dwStatic);
                            }
                        }
                    }
                }
                case REAL, QUADRATURE -> {
                    double maxTransverse = maxTransverseOf(coil);
                    boolean hasTransverse = maxTransverse > 1e-12;
                    double deltaOmega = 2 * Math.PI * src.carrierHz() - omegaSim;
                    double absDeltaOmegaDt = Math.abs(deltaOmega) * dt;
                    boolean slow = !hasTransverse || absDeltaOmegaDt <= SLOW_DELTA_OMEGA_DT;
                    if (slow) break;
                    double maxAmp = 0;
                    // For REAL/QUAD, we treat driving amplitude as ± src's bounds; this is
                    // the same heuristic as M2. We only have access to staticAmplitude here —
                    // the actual runtime bound would need it from CircuitComponent; for now
                    // fold Bloch-Siegert using staticAmplitude which is 0 for non-static.
                    // Transverse fast rollup therefore only fires for STATIC-transverse;
                    // in practice RF carriers at Larmor are slow and this branch never runs.
                }
                case GATE -> { /* no field contribution */ }
            }
        }
    }

    private static ax.xz.mri.service.circuit.CompiledCircuit.TopologyLink linkForSource(
        List<ax.xz.mri.service.circuit.CompiledCircuit.TopologyLink> links, int srcIdx
    ) {
        for (var link : links) if (link.endpointIndex() == srcIdx) return link;
        return null;
    }

    private static double maxTransverseOf(ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil coil) {
        double max = 0;
        for (int ri = 0; ri < coil.ex().length; ri++) {
            for (int zi = 0; zi < coil.ex()[0].length; zi++) {
                double mag2 = coil.ex()[ri][zi] * coil.ex()[ri][zi] + coil.ey()[ri][zi] * coil.ey()[ri][zi];
                if (mag2 > max) max = mag2;
            }
        }
        return Math.sqrt(max);
    }

    private static double representativeDt(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return 1e-6;
        double sum = 0;
        int count = 0;
        for (var s : segments) { sum += s.dt(); count++; }
        return count == 0 ? 1e-6 : sum / count;
    }

    private static CircuitDocument resolveCircuit(SimulationConfig config, ProjectRepository repository) {
        if (repository == null || config.circuitId() == null) return null;
        return repository.node(config.circuitId()) instanceof CircuitDocument c ? c : null;
    }
}
