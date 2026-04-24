package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.model.simulation.SignalTrace.Point;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integrates Bloch dynamics across the (r, z) grid and emits one complex
 * {@link SignalTrace} per {@link ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe
 * Probe} in the circuit.
 *
 * <p>Each step:
 * <ol>
 *   <li>Compute the reciprocity coupling integrals
 *       {@code S_c = Σ_p (Ex·Mx + Ey·My) dV} (and imaginary analogue) for
 *       every coil from the magnetisation state at the start of the step.
 *       Differentiate against the previous step's integrals to turn the
 *       coupling into an instantaneous EMF — that's what drives current
 *       through the receive network.</li>
 *   <li>Solve the MNA system with those coil EMFs stamped into the coil
 *       branches, plus whatever source activity the pulse step commands.
 *       The solver resolves switch / mux state, passives, and returns
 *       per-coil currents {@code I_c} and per-probe node voltages
 *       {@code V_p} (both as complex {@code (I, Q)} pairs).</li>
 *   <li>Use the coil currents to update magnetisation via Rodrigues
 *       rotation + exponential T1/T2 decay at every grid point.</li>
 *   <li>Emit the probe voltage <em>in the lab frame</em>: the MNA runs in a
 *       rotating frame at {@code ω_sim = γ·B0ref} for numerical stability,
 *       so the node voltages come out rotating-frame; multiplying by
 *       {@code exp(+j·ω_sim·t)} projects them back to the lab frame, which
 *       is the frame the schematic presents to the user. The probe's
 *       {@code demodPhaseDeg} rotation and {@code gain} are applied
 *       afterwards.</li>
 * </ol>
 */
public final class SignalTraceComputer {
    private SignalTraceComputer() {}

    public static SignalTrace compute(BlochData data, List<PulseSegment> pulse) {
        return computeAll(data, pulse).primary();
    }

    public static MultiProbeSignalTrace computeAll(BlochData data, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return MultiProbeSignalTrace.empty();
        var f = data.field();
        var circuit = f.circuit;
        if (circuit == null || circuit.probes().isEmpty()) return MultiProbeSignalTrace.empty();

        var pts = new ArrayList<FieldPoint>();
        for (int ir = 0; ir < f.rMm.length; ir++) {
            for (int iz = 0; iz < f.zMm.length; iz++) {
                pts.add(FieldInterpolator.interpolate(f, f.rMm[ir], f.zMm[iz]));
            }
        }
        int np = pts.size();
        if (np == 0) return MultiProbeSignalTrace.empty();

        var mx = new double[np];
        var my = new double[np];
        var mz = new double[np];
        for (int p = 0; p < np; p++) {
            var fp = pts.get(p);
            mx[p] = fp.mx0();
            my[p] = fp.my0();
            mz[p] = fp.mz0();
        }

        int nProbes = circuit.probes().size();
        int nCoils = circuit.coils().size();
        var traces = new ArrayList<List<Point>>(nProbes);
        double[] cosPhase = new double[nProbes];
        double[] sinPhase = new double[nProbes];
        for (int k = 0; k < nProbes; k++) {
            traces.add(new ArrayList<>());
            traces.get(k).add(new Point(0, 0, 0));
            double rad = circuit.probes().get(k).demodPhaseDeg() * Math.PI / 180.0;
            cosPhase[k] = Math.cos(rad);
            sinPhase[k] = Math.sin(rad);
        }

        var evaluator = new CircuitStepEvaluator(circuit);
        double omegaSim = f.gamma * f.b0Ref;

        // Reciprocity coupling integrals and per-step EMFs.
        double[] sCoilRePrev = new double[nCoils];
        double[] sCoilImPrev = new double[nCoils];
        double[] emfRe = new double[nCoils];
        double[] emfIm = new double[nCoils];

        double t = 0;

        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg = f.segments.get(si);
            var steps = pulse.get(si).steps();
            double dt = seg.dt();
            double e2 = Math.exp(-dt / f.t2);
            double e1 = Math.exp(-dt / f.t1);

            for (var step : steps) {
                // 1. Reciprocity coupling from M at the START of this step.
                double[] sCoilRe = new double[nCoils];
                double[] sCoilIm = new double[nCoils];
                for (int c = 0; c < nCoils; c++) {
                    double re = 0, im = 0;
                    for (int p = 0; p < np; p++) {
                        var fp = pts.get(p);
                        re += fp.coilEx()[c] * mx[p] + fp.coilEy()[c] * my[p];
                        im += fp.coilEx()[c] * my[p] - fp.coilEy()[c] * mx[p];
                    }
                    sCoilRe[c] = re;
                    sCoilIm[c] = im;
                    // Instantaneous EMF: −dΦ/dt ≈ −(S − S_prev)/dt.
                    emfRe[c] = -(re - sCoilRePrev[c]) / dt;
                    emfIm[c] = -(im - sCoilImPrev[c]) / dt;
                }

                // 2. Solve the circuit with EMF feedback.
                evaluator.evaluate(step.controls(), dt, emfRe, emfIm, t, omegaSim);

                // 3. Integrate Bloch using the solver's coil currents.
                for (int p = 0; p < np; p++) {
                    var fp = pts.get(p);
                    double bx = 0, by = 0, bz = fp.staticBz();
                    for (int c = 0; c < nCoils; c++) {
                        double I = evaluator.coilDriveI(c);
                        double Q = evaluator.coilDriveQ(c);
                        double ex = fp.coilEx()[c], ey = fp.coilEy()[c], ez = fp.coilEz()[c];
                        bx += I * ex - Q * ey;
                        by += I * ey + Q * ex;
                        bz += I * ez;
                    }
                    double bPerp2 = bx * bx + by * by;
                    if (bPerp2 < 1e-30) {
                        double om = f.gamma * bz;
                        double th = om * dt;
                        double c = Math.cos(th), s = Math.sin(th);
                        double nmx = (mx[p] * c - my[p] * s) * e2;
                        double nmy = (mx[p] * s + my[p] * c) * e2;
                        mx[p] = nmx;
                        my[p] = nmy;
                        mz[p] = 1 + (mz[p] - 1) * e1;
                    } else {
                        double bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
                        double th = f.gamma * bm * dt;
                        double nx = bx / bm, ny = by / bm, nz = bz / bm;
                        double c = Math.cos(th), s = Math.sin(th), oc = 1 - c;
                        double nd = nx * mx[p] + ny * my[p] + nz * mz[p];
                        double cx = ny * mz[p] - nz * my[p];
                        double cy = nz * mx[p] - nx * mz[p];
                        double cz = nx * my[p] - ny * mx[p];
                        mx[p] = (mx[p] * c + cx * s + nx * nd * oc) * e2;
                        my[p] = (my[p] * c + cy * s + ny * nd * oc) * e2;
                        mz[p] = 1 + (mz[p] * c + cz * s + nz * nd * oc - 1) * e1;
                    }
                }

                t += dt;
                double tUs = Math.round(t * 1e7) / 10.0;

                // 4. Emit per-probe complex voltage in the LAB frame.
                //    The MNA solves in a rotating frame at ω_sim for numerical
                //    stability; the schematic presents the lab frame to the
                //    user. Multiplying the probe's node voltage by
                //    exp(+j·ω_sim·t) undoes the rotating-frame projection so
                //    an oscilloscope hooked to the node would see the same
                //    complex sample the trace records.
                double labPhase = omegaSim * t;
                double cLab = Math.cos(labPhase), sLab = Math.sin(labPhase);
                for (int pk = 0; pk < nProbes; pk++) {
                    double simR = evaluator.probeVoltageReal(pk);
                    double simI = evaluator.probeVoltageImag(pk);
                    double labR = simR * cLab - simI * sLab;
                    double labI = simR * sLab + simI * cLab;
                    var probe = circuit.probes().get(pk);
                    double phasedR = (labR * cosPhase[pk] - labI * sinPhase[pk]) * probe.gain();
                    double phasedI = (labR * sinPhase[pk] + labI * cosPhase[pk]) * probe.gain();
                    traces.get(pk).add(new Point(tUs, phasedR, phasedI));
                }

                System.arraycopy(sCoilRe, 0, sCoilRePrev, 0, nCoils);
                System.arraycopy(sCoilIm, 0, sCoilImPrev, 0, nCoils);
            }
        }

        var byProbe = new LinkedHashMap<String, SignalTrace>();
        for (int k = 0; k < nProbes; k++) {
            byProbe.put(circuit.probes().get(k).name(), new SignalTrace(List.copyOf(traces.get(k))));
        }
        return new MultiProbeSignalTrace(Map.copyOf(byProbe), circuit.probes().get(0).name());
    }
}
