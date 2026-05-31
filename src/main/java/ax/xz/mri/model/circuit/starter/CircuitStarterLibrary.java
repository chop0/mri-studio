package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitComponent.Coil;
import ax.xz.mri.model.circuit.CircuitComponent.Mixer;
import ax.xz.mri.model.circuit.CircuitComponent.Modulator;
import ax.xz.mri.model.circuit.CircuitComponent.Multiplexer;
import ax.xz.mri.model.circuit.CircuitComponent.Probe;
import ax.xz.mri.model.circuit.CircuitComponent.VoltageMetadata;
import ax.xz.mri.model.circuit.CircuitComponent.VoltageSource;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.ProjectState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Built-in starter circuits shown in the new-circuit wizard. */
public final class CircuitStarterLibrary {
    private CircuitStarterLibrary() {}

    private static final List<CircuitStarter> STARTERS = List.of(
        new EmptyStarter(),
        new LowFieldMriStarter(),
        new NvCentreStarter()
    );

    public static List<CircuitStarter> all() { return STARTERS; }

    public static Optional<CircuitStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static CircuitStarter defaultStarter() { return STARTERS.get(1); }

    // Empty

    private static final class EmptyStarter implements CircuitStarter {
        @Override public String id() { return "empty"; }
        @Override public String name() { return "Empty"; }
        @Override public String description() { return "Blank schematic - build from scratch."; }
        @Override public Result build(ProjectNodeId id, String name, ProjectState state) {
            return Result.of(CircuitDocument.empty(id, name));
        }
    }

    // Low-field MRI

    /**
     * Standard low-field 1H MRI setup: B0 static source + coil, RF quadrature
     * source + coil, two gradient sources + coils, and one primary probe wired
     * through a T/R multiplexer. The mux routes the RF source to the RF coil
     * during TX (RF.active high) and the coil to the probe during RX, so the
     * transmitter never drives the probe's front end directly.
     */
    private static final class LowFieldMriStarter implements CircuitStarter {
        @Override public String id() { return "low-field-mri"; }
        @Override public String name() { return "Low-field 1H MRI"; }
        @Override public String description() {
            return "B0 + RF + Gx + Gz + RX probe through a T/R multiplexer gated by RF.active.";
        }

        @Override
        public Result build(ProjectNodeId id, String name, ProjectState state) {
            double b0Tesla = 0.0154;
            double gamma = 267.522e6;
            double larmorHz = gamma * b0Tesla / (2 * Math.PI);

            var newEigenfields = new ArrayList<EigenfieldDocument>();
            // Shimmed B0 (Helmholtz + a small residual z-gradient) so the static
            // field varies across the sample — that inhomogeneity is what a CPMG
            // echo train dephases and refocuses into visible echoes. The ideal
            // helmholtz-b0 (no shim) stays available for uniform-field setups.
            var b0Eigen = ensureEigenfield(state, "B0 Helmholtz (shim)", "helmholtz-b0-shim", newEigenfields);
            var gxEigen = ensureEigenfield(state, "Gradient X", "gradient-x", newEigenfields);
            var gzEigen = ensureEigenfield(state, "Gradient Z", "gradient-z", newEigenfields);
            var rfEigen = ensureEigenfield(state, "RF Transverse", "uniform-b-perp", newEigenfields);

            var b0Src = new VoltageSource(new ComponentId("src-b0"), "B0",
                AmplitudeKind.STATIC, 0, 0, b0Tesla, 0);
            // RF drive is now two REAL envelopes (I and Q) fed into a
            // Modulator that upconverts to the Larmor carrier. No more
            // special-case QUADRATURE amplitude kind.
            var rfISrc = new VoltageSource(new ComponentId("src-rf-i"), "RF I",
                AmplitudeKind.REAL, 0, -200e-6, 200e-6, 0);
            var rfQSrc = new VoltageSource(new ComponentId("src-rf-q"), "RF Q",
                AmplitudeKind.REAL, 0, -200e-6, 200e-6, 0);
            var gxSrc = new VoltageSource(new ComponentId("src-gx"), "Gradient X",
                AmplitudeKind.REAL, 0, -0.030, 0.030, 0);
            var gzSrc = new VoltageSource(new ComponentId("src-gz"), "Gradient Z",
                AmplitudeKind.REAL, 0, -0.030, 0.030, 0);

            // Each coil makes its impedance and sensitivity explicit. R = 1 Ω
            // and sensitivity = 1 T/A means the source amplitude (in volts)
            // numerically equals the coil current (in amps), which equals the
            // peak |B| (in tesla) — a useful default that keeps the legible
            // "amplitude in tesla" UX of the old hidden defaults but exposes
            // both knobs in the inspector for users who want a different
            // calibration.
            double coilR = 1.0;
            double coilSensitivity = 1.0;
            var b0Coil = new Coil(new ComponentId("coil-b0"), "B0 Coil", b0Eigen.id(),
                0, coilR, coilSensitivity);
            var rfCoil = new Coil(new ComponentId("coil-rf"), "RF Coil", rfEigen.id(),
                0, coilR, coilSensitivity);
            var gxCoil = new Coil(new ComponentId("coil-gx"), "Gx Coil", gxEigen.id(),
                0, coilR, coilSensitivity);
            var gzCoil = new Coil(new ComponentId("coil-gz"), "Gz Coil", gzEigen.id(),
                0, coilR, coilSensitivity);

            // Modulator composes the I/Q envelopes into an upconverted RF
            // signal at the Larmor carrier. Its "out" drives the T/R mux;
            // "in0"/"in1" wire to the two REAL sources.
            var rfModulator = new Modulator(new ComponentId("mod-rf"), "RF Modulator", larmorHz);

            // T/R mux: common -> RF coil, a -> modulator out, b -> Mixer.
            // closed resistance deliberately tiny so the mux doesn't form a
            // noticeable voltage divider against the 1 Ω default coil R.
            var trMux = new Multiplexer(new ComponentId("mux-tr"), "T/R Mux",
                1e-6, 1e9, 0.5);
            // Metadata tap observes the Modulator by name — the compiler
            // expands a modulator reference into the OR of its I and Q
            // source activity, so either envelope playing lights the gate.
            var rfActive = new VoltageMetadata(new ComponentId("meta-rf-active"), "RF active", rfModulator.name());
            // Mixer between the mux's RX port and the probe. Its LO is set
            // to the Larmor carrier so the probe's complex trace comes back
            // baseband relative to the RF drive — Point.real = I,
            // Point.imag = Q of the demodulated envelope.
            var rxMixer = new Mixer(new ComponentId("dc-rx"), "I/Q Demod", larmorHz);
            // Probe is a pure voltmeter — demod lives in the Mixer block.
            var probe = new Probe(new ComponentId("probe-rx"), "Primary RX",
                1.0, 0.0, Double.POSITIVE_INFINITY);

            // Proton substance: 1H water at standard tissue T1/T2 with the
            // proton γ. Explicit — no silent fallback. The Substance block
            // anchors the substance into the FOV; magnetic coupling is
            // ambient via reciprocity (no wires needed).
            var protonDoc = new ax.xz.mri.project.SubstanceDocument(
                new ProjectNodeId("substance-" + java.util.UUID.randomUUID()),
                name + " water",
                ax.xz.mri.model.substance.ContinuousMagnetisation.defaults());  // 1H water, 5×5×50 vox over ±30 mm × ±30 mm × ±10 mm
            var protonBlock = new CircuitComponent.Substance(
                new ComponentId("sub-water"), "Water",
                protonDoc.id(),
                CircuitComponent.Substance.Kind.CONTINUOUS_MAGNETISATION);

            var components = List.<CircuitComponent>of(
                b0Src, rfISrc, rfQSrc, gxSrc, gzSrc,
                b0Coil, rfCoil, gxCoil, gzCoil,
                rfModulator, trMux, rfActive, rxMixer, probe,
                protonBlock);

            var wires = new ArrayList<Wire>();
            wires.add(wire("w-b0-drive", b0Src.id(), "out", b0Coil.id(), "in"));
            wires.add(wire("w-gx-drive", gxSrc.id(), "out", gxCoil.id(), "in"));
            wires.add(wire("w-gz-drive", gzSrc.id(), "out", gzCoil.id(), "in"));

            // T/R routing. Transmit path: RF I → mod.in0, RF Q → mod.in1,
            // mod.out → mux.a. Receive path: RF coil → mux.common,
            // mux.b → mixer.in, mixer.out0 → probe.in (real channel / I).
            // Second mixer output (Q / phase) stays unwired; the user can
            // drop another probe on out1 if they want both channels.
            wires.add(wire("w-rfi-mod",     rfISrc.id(), "out", rfModulator.id(), "in0"));
            wires.add(wire("w-rfq-mod",     rfQSrc.id(), "out", rfModulator.id(), "in1"));
            wires.add(wire("w-mod-mux",     rfModulator.id(), "out", trMux.id(), "a"));
            wires.add(wire("w-mux-mixer",   trMux.id(), "b", rxMixer.id(), "in"));
            wires.add(wire("w-mixer-probe", rxMixer.id(), "out0", probe.id(), "in"));
            wires.add(wire("w-mux-coil",    trMux.id(), "common", rfCoil.id(), "in"));
            // RF-active metadata tap drives mux.ctl. Source reference is
            // by name, not by wire.
            wires.add(wire("w-meta-ctl",    rfActive.id(), "out", trMux.id(), "ctl"));

            var layout = LowFieldLayout.arrange(
                List.of(b0Src, rfISrc, rfQSrc, gxSrc, gzSrc),
                List.of(b0Coil, rfCoil, gxCoil, gzCoil),
                rfModulator, trMux, rfActive, rxMixer, probe, protonBlock);

            return new Result(
                new CircuitDocument(id, name, components, wires, layout),
                newEigenfields,
                List.of(protonDoc));
        }
    }

    // NV-centre diamond

    /**
     * NV-centre diamond starter: a linear array of NV centres at the sample
     * surface biased by a Helmholtz-pair B0 and sensing a buried
     * dipole-pair source. Two STATIC sources at 0 V default — the user
     * dials the bias in tesla and the sample amplitude (also in tesla,
     * thanks to the {@code 1 T/A} default sensitivity) from the inspector.
     *
     * <p>No RF coil yet — the v1 procedure-side atomic Ramsey block doesn't
     * route MW pulses through the schematic. Pulse-level NV simulation
     * (v1.1) will add a microwave coil here.
     */
    private static final class NvCentreStarter implements CircuitStarter {
        @Override public String id() { return "nv-diamond"; }
        @Override public String name() { return "NV centre diamond"; }
        @Override public String description() {
            return "Helmholtz B0 + Sample (Lorentzian dipole) + MW I/Q drive into a modulator + "
                + "MW coil + Laser gate + NV-diamond substance block + photon counter on clicks_red.";
        }

        @Override
        public Result build(ProjectNodeId id, String name, ProjectState state) {
            var newEigenfields = new ArrayList<EigenfieldDocument>();
            var b0Eigen     = ensureEigenfield(state, "B0 Helmholtz",           "helmholtz-b0",      newEigenfields);
            var sampleEigen = ensureEigenfield(state, "Lorentzian dipole pair", "lorentzian-dipole", newEigenfields);
            var mwEigen     = ensureEigenfield(state, "RF Transverse",          "uniform-b-perp",    newEigenfields);
            var gradXEigen  = ensureEigenfield(state, "Gradient X",             "gradient-x",        newEigenfields);

            // STATIC sources: B0 bias defaults to 10 mT (matching the NV
            // SimConfigTemplate's b0Ref, so the NV is on-resonance out of the
            // box). For STATIC the steady-state coil drive comes from the
            // source's maxAmplitude. Sample defaults to 50 nT — the
            // Lorentzian-dipole eigenfield is peak-normalised to 1 T per
            // unit drive, so 50 nT amplitude × 1 T / unit = 50 nT peak field
            // at the NV layer. This matches the NV-adaptive-coherent GP
            // prior (GP_AMP_T = 50 nT), so the Bayesian procedure can
            // immediately estimate a non-zero ground truth.
            var b0Src = new VoltageSource(new ComponentId("src-b0"), "B0",
                AmplitudeKind.STATIC, 0, 0, 0.01, 0);
            var sampleSrc = new VoltageSource(new ComponentId("src-sample"), "Sample",
                AmplitudeKind.STATIC, 0, 0, 50e-9, 0);

            // Microwave I/Q drive. The Modulator upconverts to the NV Larmor
            // carrier (γ_e · b0Ref / 2π ≈ 280 MHz at 10 mT). Default Larmor for
            // a 10 mT bias; user can edit b0Ref + carrier independently.
            double nvGammaHz = 28.024e9;                    // γ_e / 2π in Hz/T
            double b0RefT    = 0.01;                        // matches NV_CENTRE_DIAMOND template
            double mwCarrier = nvGammaHz * b0RefT;          // ≈ 280.24 MHz
            var mwISrc = new VoltageSource(new ComponentId("src-mw-i"), "MW I",
                AmplitudeKind.REAL, 0, -1e-4, 1e-4, 0);     // ±100 µT envelope
            var mwQSrc = new VoltageSource(new ComponentId("src-mw-q"), "MW Q",
                AmplitudeKind.REAL, 0, -1e-4, 1e-4, 0);
            var mwModulator = new Modulator(new ComponentId("mod-mw"), "MW Modulator", mwCarrier);

            // Gradient X: a REAL source the adaptive procedure modulates per
            // Ramsey block to gain spatial information about the sample. The
            // gradient-x eigenfield is Bz(x) = x (T/m at unit drive), so
            // amplitude g (T/m) gives Bz = g·x_n at every NV.
            var gradXSrc = new VoltageSource(new ComponentId("src-grad-x"), "Grad X",
                AmplitudeKind.REAL, 0, -10.0, 10.0, 0);     // ±10 T/m envelope

            // Laser: a GATE-kind source driving the substance's laser_on
            // control input. 1 = laser on (pump or read), 0 = dark.
            var laserSrc = new VoltageSource(new ComponentId("src-laser"), "Laser",
                AmplitudeKind.GATE, 0, 0, 1, 0);

            double coilR = 1.0;
            double coilSensitivity = 1.0;
            var b0Coil = new Coil(new ComponentId("coil-b0"), "B0 Coil", b0Eigen.id(),
                0, coilR, coilSensitivity);
            var sampleCoil = new Coil(new ComponentId("coil-sample"), "Sample Coil", sampleEigen.id(),
                0, coilR, coilSensitivity);
            var mwCoil = new Coil(new ComponentId("coil-mw"), "MW Coil", mwEigen.id(),
                0, coilR, coilSensitivity);
            var gradXCoil = new Coil(new ComponentId("coil-grad-x"), "Grad X Coil", gradXEigen.id(),
                0, coilR, coilSensitivity);

            // Fresh diamond substance — 16 NV centres in a 1-µm linear array.
            var diamondDoc = new SubstanceDocument(
                new ProjectNodeId("substance-" + java.util.UUID.randomUUID()),
                name + " diamond",
                new NvEnsemble(
                    new NvArrayGeometry(NvArrayShape.LINEAR_X_UNIFORM, 16, 1e-6, 50e-9,
                        NvAxis.AXIS_PLUS_Z, 0L),
                    NvPhysics.defaults(),
                    0L,
                    0.0));

            var substanceBlock = new CircuitComponent.Substance(
                new ComponentId("sub-diamond"), "Diamond",
                diamondDoc.id(),
                CircuitComponent.Substance.Kind.NV);

            var counter = new CircuitComponent.OpticalCounter(
                new ComponentId("oc-red"), "Red counter",
                0.85, 50.0, 0L);

            var components = List.<CircuitComponent>of(
                b0Src, sampleSrc,
                mwISrc, mwQSrc, mwModulator,
                gradXSrc, laserSrc,
                b0Coil, sampleCoil, mwCoil, gradXCoil,
                substanceBlock,
                counter);

            var wires = new ArrayList<Wire>();
            wires.add(wire("w-b0-drive",     b0Src.id(),     "out", b0Coil.id(),     "in"));
            wires.add(wire("w-sample-drive", sampleSrc.id(), "out", sampleCoil.id(), "in"));
            wires.add(wire("w-mwi-mod",      mwISrc.id(),    "out", mwModulator.id(), "in0"));
            wires.add(wire("w-mwq-mod",      mwQSrc.id(),    "out", mwModulator.id(), "in1"));
            wires.add(wire("w-mw-drive",     mwModulator.id(), "out", mwCoil.id(),   "in"));
            wires.add(wire("w-grad-x-drive", gradXSrc.id(),  "out", gradXCoil.id(),  "in"));
            wires.add(wire("w-laser-pump",   laserSrc.id(),  "out", substanceBlock.id(), "laser_on"));
            wires.add(wire("w-clicks-red",   substanceBlock.id(), "clicks_red", counter.id(), "in"));

            var layout = NvCentreLayout.arrange(
                List.of(b0Src, sampleSrc, mwISrc, mwQSrc, gradXSrc),
                List.of(b0Coil, sampleCoil, mwCoil, gradXCoil),
                mwModulator, laserSrc,
                substanceBlock,
                counter);

            return new Result(
                new CircuitDocument(id, name, components, wires, layout),
                newEigenfields,
                List.of(diamondDoc));
        }
    }

    /**
     * Look up an eigenfield by (name, script) match, or fabricate a fresh
     * record. Newly-fabricated records are appended to {@code freshSink} so
     * the caller can dispatch structural mutations to add them.
     */
    private static EigenfieldDocument ensureEigenfield(
        ProjectState repo, String name, String starterId, List<EigenfieldDocument> freshSink
    ) {
        var s = EigenfieldStarterLibrary.byId(starterId).orElseThrow();
        for (var id : repo.eigenfieldIds()) {
            var ef = repo.eigenfield(id);
            if (ef != null && ef.name().equals(name) && ef.script().equals(s.source())) return ef;
        }
        var fresh = new EigenfieldDocument(
            new ProjectNodeId("ef-" + java.util.UUID.randomUUID()),
            name, s.description(), s.source(), s.units());
        freshSink.add(fresh);
        return fresh;
    }

    private static Wire wire(String id, ComponentId a, String pa, ComponentId b, String pb) {
        return new Wire(id, new ComponentTerminal(a, pa), new ComponentTerminal(b, pb));
    }
}
