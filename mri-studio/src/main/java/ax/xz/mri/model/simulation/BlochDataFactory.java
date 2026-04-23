package ax.xz.mri.model.simulation;

import ax.xz.mri.model.field.DynamicFieldMap;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.GateMap;
import ax.xz.mri.model.field.ReceiveCoilMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a runtime {@link FieldMap} from a {@link SimulationConfig} and a
 * baked sequence's segments.
 *
 * <p>The simulator runs in a rotating frame at {@code ω_s = γ · referenceB0Tesla}.
 * Each {@link DrivePath} in the config is classified against its
 * {@link TransmitCoil}'s eigenfield:
 *
 * <ul>
 *   <li><b>{@link AmplitudeKind#STATIC STATIC}</b> paths: eigenfield
 *       evaluated once, scaled by {@code maxAmplitude}, accumulated into the
 *       static Bz map (reference subtracted at the end).</li>
 *   <li><b>{@link AmplitudeKind#REAL REAL}</b> paths at carrier 0 (gradients):
 *       kept as dynamic fields; only their Bz component contributes in the
 *       rotating frame.</li>
 *   <li><b>{@link AmplitudeKind#QUADRATURE QUADRATURE}</b> paths near
 *       resonance: kept as dynamic fields with full (Ex, Ey, Ez) shape.</li>
 *   <li><b>Fast off-resonance fields</b>: transverse part folded into the
 *       static Bz via Bloch–Siegert; only longitudinal amplitude flows.</li>
 *   <li><b>{@link AmplitudeKind#GATE GATE}</b> paths: no B-field contribution.
 *       They carry pulse-step control scalars that the simulator exposes as
 *       named gate signals — other paths and receive coils can read them.</li>
 * </ul>
 *
 * <h3>Grid selection</h3>
 * Each {@link EigenfieldDocument} declares a {@link FieldSymmetry}. If every
 * involved eigenfield is {@link FieldSymmetry#AXISYMMETRIC_Z}, the factory
 * uses the 2D (r, z) grid: field values at azimuth 0 broadcast to every
 * azimuth by the simulator's rotating-frame invariance. A
 * {@link FieldSymmetry#CARTESIAN_3D} declaration reserves the right to a
 * full (x, y, z) grid; while the 3D grid backend is not yet wired up, the
 * factory rejects configurations that require it with a pointer to the
 * future work so users can't silently get wrong physics out of an
 * azimuthally-varying script.
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
        assertAxisymmetricOrExplain(config, repository);

        var field = new FieldMap();

        int nR = Math.max(2, config.nR());
        int nZ = Math.max(2, config.nZ());

        field.rMm = new double[nR];
        field.zMm = new double[nZ];
        for (int i = 0; i < nR; i++) {
            field.rMm[i] = i * config.fovRMm() / (nR - 1);
        }
        for (int i = 0; i < nZ; i++) {
            field.zMm[i] = -config.fovZMm() / 2 + i * config.fovZMm() / (nZ - 1);
        }

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
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                field.mz0[ri][zi] = 1.0;
            }
        }

        double[][] staticBz = new double[nR][nZ];
        var dynamics = new ArrayList<DynamicFieldMap>();
        var gates = new ArrayList<GateMap>();
        int channelCursor = 0;
        double omegaSim = config.gamma() * config.referenceB0Tesla();
        double dt = config.dtSeconds() > 0 ? config.dtSeconds() : representativeDt(segments);

        Map<String, TransmitCoil> coilsByName = new HashMap<>();
        for (var c : config.transmitCoils()) coilsByName.put(c.name(), c);

        for (var path : config.drivePaths()) {
            if (path.isGate()) {
                gates.add(new GateMap(path.name(), channelCursor));
                channelCursor += path.channelCount();
                continue;
            }

            var coil = coilsByName.get(path.transmitCoilName());
            var eigen = coil == null ? null : resolveEigenfield(coil.eigenfieldId(), repository);
            EigenfieldScript script = eigen != null ? compile(eigen.script()) : (x, y, z) -> Vec3.ZERO;
            double defaultMagnitude = eigen != null ? eigen.defaultMagnitude() : 1.0;

            double[][] ex = new double[nR][nZ];
            double[][] ey = new double[nR][nZ];
            double[][] ez = new double[nR][nZ];
            sampleEigenfield(script, field.rMm, field.zMm, ex, ey, ez);

            if (defaultMagnitude != 1.0) {
                for (int ri = 0; ri < nR; ri++) {
                    for (int zi = 0; zi < nZ; zi++) {
                        ex[ri][zi] *= defaultMagnitude;
                        ey[ri][zi] *= defaultMagnitude;
                        ez[ri][zi] *= defaultMagnitude;
                    }
                }
            }

            double maxTransverse = 0;
            for (int ri = 0; ri < nR; ri++) {
                for (int zi = 0; zi < nZ; zi++) {
                    double mag2 = ex[ri][zi] * ex[ri][zi] + ey[ri][zi] * ey[ri][zi];
                    if (mag2 > maxTransverse) maxTransverse = mag2;
                }
            }
            maxTransverse = Math.sqrt(maxTransverse);
            boolean hasTransverse = maxTransverse > 1e-12;

            double deltaOmega = 2 * Math.PI * path.carrierHz() - omegaSim;
            double absDeltaOmegaDt = Math.abs(deltaOmega) * dt;

            switch (path.kind()) {
                case STATIC -> {
                    for (int ri = 0; ri < nR; ri++) {
                        for (int zi = 0; zi < nZ; zi++) {
                            staticBz[ri][zi] += path.maxAmplitude() * ez[ri][zi];
                        }
                    }
                    if (hasTransverse && Math.abs(omegaSim) > 1e-9) {
                        double dwStatic = -omegaSim;
                        double ampStatic = path.maxAmplitude();
                        double amp2 = ampStatic * ampStatic;
                        for (int ri = 0; ri < nR; ri++) {
                            for (int zi = 0; zi < nZ; zi++) {
                                double bPerp2 = amp2 * (ex[ri][zi] * ex[ri][zi] + ey[ri][zi] * ey[ri][zi]);
                                staticBz[ri][zi] += config.gamma() * bPerp2 / (4 * dwStatic);
                            }
                        }
                    }
                }
                case REAL, QUADRATURE -> {
                    boolean slow = !hasTransverse || absDeltaOmegaDt <= SLOW_DELTA_OMEGA_DT;

                    double maxAmp = Math.max(Math.abs(path.maxAmplitude()), Math.abs(path.minAmplitude()));
                    double transverseDriving = config.gamma() * maxAmp * maxTransverse * dt;
                    if (!slow && transverseDriving > STRONG_DRIVING_DT) {
                        throw new IllegalStateException(
                            "DrivePath '" + path.name() + "' has carrier " + path.carrierHz() +
                            " Hz with |Δω·dt| = " + absDeltaOmegaDt +
                            " and strong transverse driving γ·|B\u22a5|·dt = " + transverseDriving +
                            ". Either increase the step rate or move the carrier near ω_s/(2π) = " +
                            (omegaSim / (2 * Math.PI)) + " Hz.");
                    }

                    double[][] effEx = ex;
                    double[][] effEy = ey;

                    if (!slow) {
                        double amp2 = maxAmp * maxAmp;
                        for (int ri = 0; ri < nR; ri++) {
                            for (int zi = 0; zi < nZ; zi++) {
                                double bPerp2 = amp2 * (ex[ri][zi] * ex[ri][zi] + ey[ri][zi] * ey[ri][zi]);
                                staticBz[ri][zi] += config.gamma() * bPerp2 / (4 * deltaOmega);
                            }
                        }
                        effEx = new double[nR][nZ];
                        effEy = new double[nR][nZ];
                    }

                    int count = path.channelCount();
                    dynamics.add(new DynamicFieldMap(path.name(), channelCursor, count,
                        path.carrierHz(), deltaOmega,
                        effEx, effEy, ez));
                    channelCursor += count;
                }
                case GATE -> throw new IllegalStateException("Unreachable: gate handled earlier");
            }
        }

        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                staticBz[ri][zi] -= config.referenceB0Tesla();
            }
        }

        field.staticBz = staticBz;
        field.dynamicFields = List.copyOf(dynamics);
        field.gates = List.copyOf(gates);
        field.receiveCoils = buildReceiveCoilMaps(config, nR, nZ, field.rMm, field.zMm, repository);
        return field;
    }

    private static List<ReceiveCoilMap> buildReceiveCoilMaps(
        SimulationConfig config, int nR, int nZ, double[] rMm, double[] zMm, ProjectRepository repository) {
        var gateLookup = new HashMap<String, Integer>();
        int cursor = 0;
        for (var path : config.drivePaths()) {
            if (path.isGate()) gateLookup.put(path.name(), cursor);
            cursor += path.channelCount();
        }

        var out = new ArrayList<ReceiveCoilMap>();
        for (var coil : config.receiveCoils()) {
            var eigen = resolveEigenfield(coil.eigenfieldId(), repository);
            EigenfieldScript script = eigen != null ? compile(eigen.script()) : (x, y, z) -> Vec3.ZERO;
            double defaultMagnitude = eigen != null ? eigen.defaultMagnitude() : 1.0;

            double[][] ex = new double[nR][nZ];
            double[][] ey = new double[nR][nZ];
            double[][] ez = new double[nR][nZ];
            sampleEigenfield(script, rMm, zMm, ex, ey, ez);
            if (defaultMagnitude != 1.0) {
                for (int ri = 0; ri < nR; ri++) {
                    for (int zi = 0; zi < nZ; zi++) {
                        ex[ri][zi] *= defaultMagnitude;
                        ey[ri][zi] *= defaultMagnitude;
                        ez[ri][zi] *= defaultMagnitude;
                    }
                }
            }

            Integer gateChannel = coil.acquisitionGateName() == null ? null : gateLookup.get(coil.acquisitionGateName());
            if (coil.acquisitionGateName() != null && gateChannel == null) {
                throw new IllegalStateException(
                    "ReceiveCoil '" + coil.name() + "' references unknown acquisition gate '" + coil.acquisitionGateName() + "'");
            }
            out.add(new ReceiveCoilMap(coil.name(), coil.gain(), coil.phaseDeg(),
                gateChannel == null ? -1 : gateChannel, ex, ey, ez));
        }
        return List.copyOf(out);
    }

    private static void assertAxisymmetricOrExplain(SimulationConfig config, ProjectRepository repository) {
        if (repository == null) return;
        for (var coil : config.transmitCoils()) requireAxisymmetric(coil.eigenfieldId(), "transmit coil '" + coil.name() + "'", repository);
        for (var coil : config.receiveCoils()) requireAxisymmetric(coil.eigenfieldId(), "receive coil '" + coil.name() + "'", repository);
    }

    private static void requireAxisymmetric(ProjectNodeId id, String ownerLabel, ProjectRepository repository) {
        var eigen = resolveEigenfield(id, repository);
        if (eigen == null) return;
        if (eigen.symmetry() != FieldSymmetry.AXISYMMETRIC_Z) {
            throw new IllegalStateException(
                ownerLabel + " uses eigenfield '" + eigen.name() + "' declared with symmetry " +
                eigen.symmetry() + ", which is not yet supported by the 2D (r, z) grid. " +
                "Either change the symmetry declaration or wait for the 3D grid backend.");
        }
    }

    private static double representativeDt(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return 1e-6;
        double sum = 0;
        int count = 0;
        for (var s : segments) {
            sum += s.dt();
            count++;
        }
        return count == 0 ? 1e-6 : sum / count;
    }

    private static EigenfieldDocument resolveEigenfield(ProjectNodeId id, ProjectRepository repository) {
        if (repository == null || id == null) return null;
        return repository.node(id) instanceof EigenfieldDocument ef ? ef : null;
    }

    private static EigenfieldScript compile(String source) {
        try {
            return EigenfieldScriptEngine.compile(source);
        } catch (RuntimeException ex) {
            return (x, y, z) -> Vec3.ZERO;
        }
    }

    private static void sampleEigenfield(EigenfieldScript script, double[] rMm, double[] zMm,
                                         double[][] ex, double[][] ey, double[][] ez) {
        int nR = rMm.length;
        int nZ = zMm.length;
        for (int ri = 0; ri < nR; ri++) {
            double xMeters = rMm[ri] * 1e-3;
            for (int zi = 0; zi < nZ; zi++) {
                double zMeters = zMm[zi] * 1e-3;
                Vec3 v;
                try {
                    v = script.evaluate(xMeters, 0, zMeters);
                } catch (Throwable t) {
                    v = Vec3.ZERO;
                }
                if (v == null) v = Vec3.ZERO;
                ex[ri][zi] = Double.isFinite(v.x()) ? v.x() : 0;
                ey[ri][zi] = Double.isFinite(v.y()) ? v.y() : 0;
                ez[ri][zi] = Double.isFinite(v.z()) ? v.z() : 0;
            }
        }
    }
}
