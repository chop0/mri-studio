package ax.xz.mri.model.simulation;

import ax.xz.mri.model.field.DynamicFieldMap;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.ReceiveCoilMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a runtime {@link FieldMap} from a {@link SimulationConfig} and a
 * baked sequence's segments.
 *
 * <p>The simulator runs in a rotating frame at
 * {@code ω_s = γ · referenceB0Tesla}. Each field in the config is classified
 * and contributes as follows:
 *
 * <ul>
 *   <li><b>{@code STATIC}</b> fields: eigenfield evaluated at each grid point,
 *       scaled by {@code maxAmplitude}, accumulated into a lab-frame static
 *       Bz map. The rotating-frame static Bz is obtained by subtracting
 *       {@code referenceB0Tesla}.</li>
 *   <li><b>{@code REAL}</b> fields at carrier 0 (typical gradients): kept as
 *       a dynamic field whose Bz-component is sampled on the grid. No
 *       transverse contribution in the rotating frame (DC transverse is
 *       Bloch–Siegert-dropped; see fast handling below).</li>
 *   <li><b>{@code QUADRATURE}</b> fields near resonance (|Δω·dt| ≪ 1): kept
 *       as a dynamic field with full (Ex, Ey, Ez) shape. The simulator
 *       applies the amplitude directly (I, Q).</li>
 *   <li><b>Fast fields</b> ({@code |Δω·dt| > threshold}): their transverse
 *       contribution is folded into {@code staticBz} as a closed-form
 *       Bloch–Siegert correction {@code γ · |B⊥|² / (4·Δω)}. The dynamic-field
 *       entry is kept with transverse components zeroed — only longitudinal
 *       amplitude still flows through to the simulator.</li>
 * </ul>
 *
 * <p>Strong-off-resonance configurations — where the fast field also has
 * strong driving ({@code γ · |B⊥| · dt} ≳ 1) — are physically pathological
 * and rejected at build time with a descriptive error.
 */
public final class BlochDataFactory {
    /** Conservative Nyquist-ish threshold for calling a field "slow". */
    private static final double SLOW_DELTA_OMEGA_DT = 0.1;

    /** Threshold above which a fast field's driving is too strong for Bloch–Siegert. */
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

        // Thermal equilibrium
        field.mx0 = new double[nR][nZ];
        field.my0 = new double[nR][nZ];
        field.mz0 = new double[nR][nZ];
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                field.mz0[ri][zi] = 1.0;
            }
        }

        // Work arrays
        double[][] staticBz = new double[nR][nZ];
        var dynamics = new ArrayList<DynamicFieldMap>();
        int channelCursor = 0;
        double omegaSim = config.gamma() * config.referenceB0Tesla();

        // Representative dt — the step used when reasoning about Nyquist for
        // fast-field classification. Prefer the config's dt (authoritative); fall back
        // to the segment-averaged dt for legacy paths that may pass mismatched data.
        double dt = config.dtSeconds() > 0 ? config.dtSeconds() : representativeDt(segments);

        for (var def : config.fields()) {
            var eigen = resolveEigenfield(def, repository);
            EigenfieldScript script = eigen != null ? compile(eigen.script()) : (x, y, z) -> Vec3.ZERO;
            double defaultMagnitude = eigen != null ? eigen.defaultMagnitude() : 1.0;

            double[][] ex = new double[nR][nZ];
            double[][] ey = new double[nR][nZ];
            double[][] ez = new double[nR][nZ];
            sampleEigenfield(script, field.rMm, field.zMm, ex, ey, ez);

            // Bake the eigenfield's defaultMagnitude into the sampled vectors so the
            // downstream math (amplitude × sample) yields the physical field directly
            // in the eigenfield's declared units. Unit-normalised scripts have
            // defaultMagnitude = 1 and this is a no-op.
            if (defaultMagnitude != 1.0) {
                for (int ri = 0; ri < nR; ri++) {
                    for (int zi = 0; zi < nZ; zi++) {
                        ex[ri][zi] *= defaultMagnitude;
                        ey[ri][zi] *= defaultMagnitude;
                        ez[ri][zi] *= defaultMagnitude;
                    }
                }
            }

            // The rotating-frame transformation only affects the transverse
            // component of the eigenfield. Longitudinal components flow through
            // at whatever rate the amplitude schedule demands, independent of
            // any carrier offset. So classify fast/slow based on the transverse
            // magnitude of the eigenfield, not just |Δω·dt|.
            double maxTransverse = 0;
            for (int ri = 0; ri < nR; ri++) {
                for (int zi = 0; zi < nZ; zi++) {
                    double mag2 = ex[ri][zi] * ex[ri][zi] + ey[ri][zi] * ey[ri][zi];
                    if (mag2 > maxTransverse) maxTransverse = mag2;
                }
            }
            maxTransverse = Math.sqrt(maxTransverse);
            boolean hasTransverse = maxTransverse > 1e-12;

            double deltaOmega = 2 * Math.PI * def.carrierHz() - omegaSim;
            double absDeltaOmegaDt = Math.abs(deltaOmega) * dt;

            switch (def.kind()) {
                case STATIC -> {
                    // Always-on. Longitudinal part contributes directly to the static
                    // lab-frame field; reference is subtracted below.
                    for (int ri = 0; ri < nR; ri++) {
                        for (int zi = 0; zi < nZ; zi++) {
                            staticBz[ri][zi] += def.maxAmplitude() * ez[ri][zi];
                        }
                    }
                    // Transverse part: would rotate at −ω_s in the rotating frame.
                    // Only do the Bloch–Siegert roll-up when there IS a transverse
                    // component to roll up. For purely longitudinal statics (the
                    // typical B0 coil), this is a no-op.
                    if (hasTransverse && Math.abs(omegaSim) > 1e-9) {
                        double dwStatic = -omegaSim;
                        double ampStatic = def.maxAmplitude();
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
                    // Longitudinal-only (e.g. a gradient) is always slow in the rotating
                    // frame regardless of carrier — no transverse component to oscillate.
                    boolean slow = !hasTransverse || absDeltaOmegaDt <= SLOW_DELTA_OMEGA_DT;

                    // Strong-driving check applies only to the transverse component:
                    // γ·|B⊥_max|·dt ≳ 1 means Rodrigues on transverse is untrustworthy.
                    double maxAmp = Math.max(Math.abs(def.maxAmplitude()), Math.abs(def.minAmplitude()));
                    double transverseDriving = config.gamma() * maxAmp * maxTransverse * dt;
                    if (!slow && transverseDriving > STRONG_DRIVING_DT) {
                        throw new IllegalStateException(
                            "Field '" + def.name() + "' has carrier " + def.carrierHz() +
                            " Hz with |Δω·dt| = " + absDeltaOmegaDt +
                            " and strong transverse driving γ·|B\u22a5|·dt = " + transverseDriving +
                            ". This configuration cannot be simulated at the chosen sample rate; either " +
                            "increase the step rate or change the carrier to be near ω_s/(2π) = " +
                            (omegaSim / (2 * Math.PI)) + " Hz.");
                    }

                    double[][] effEx = ex;
                    double[][] effEy = ey;

                    if (!slow) {
                        // Fast-weak regime: fold transverse into Bloch–Siegert.
                        // Leading correction: δBz = γ · |B⊥|² · (maxAmp)² / (4 · Δω).
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

                    int count = def.channelCount();
                    dynamics.add(new DynamicFieldMap(def.name(), channelCursor, count,
                        def.carrierHz(), deltaOmega,
                        effEx, effEy, ez));
                    channelCursor += count;
                }
            }
        }

        // Subtract the reference to put staticBz in the rotating frame.
        for (int ri = 0; ri < nR; ri++) {
            for (int zi = 0; zi < nZ; zi++) {
                staticBz[ri][zi] -= config.referenceB0Tesla();
            }
        }

        field.staticBz = staticBz;
        field.dynamicFields = List.copyOf(dynamics);
        field.receiveCoils = buildReceiveCoilMaps(config, nR, nZ, field.rMm, field.zMm, repository);
        return field;
    }

    private static List<ReceiveCoilMap> buildReceiveCoilMaps(
        SimulationConfig config, int nR, int nZ, double[] rMm, double[] zMm, ProjectRepository repository) {
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
            out.add(new ReceiveCoilMap(coil.name(), coil.gain(), coil.phaseDeg(), ex, ey, ez));
        }
        return List.copyOf(out);
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

    private static EigenfieldDocument resolveEigenfield(FieldDefinition def, ProjectRepository repository) {
        return resolveEigenfield(def.eigenfieldId(), repository);
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
