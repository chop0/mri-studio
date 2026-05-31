package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Built-in starter sequences shown in the new-sequence wizard. */
public final class SequenceStarterLibrary {
    private SequenceStarterLibrary() {}

    static final double FALLBACK_T90_MICROS = 30.0;
    static final double DEFAULT_DT_MICROS = 1.0;

    private static final SequenceStarter BLANK = new BlankStarter();
    private static final SequenceStarter CPMG = new CarrPurcellStarter(
        "cpmg", "CPMG",
        "90 excitation on x, then 180 refocusing pulses on y. Robust T2 measurement.",
        true);
    private static final SequenceStarter CP = new CarrPurcellStarter(
        "cp", "Carr-Purcell (CP)",
        "90 excitation on x, then 180 refocusing pulses on x. Sensitive to B1 inhomogeneity.",
        false);
    private static final SequenceStarter NV_RAMSEY = new NvRamseyStarter();

    private static final List<SequenceStarter> STARTERS = List.of(BLANK, CPMG, CP, NV_RAMSEY);

    public static List<SequenceStarter> all() { return STARTERS; }

    public static Optional<SequenceStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static SequenceStarter defaultStarter() { return BLANK; }

    public static double computeT90Micros(double gamma, double b1Max) {
        double rabi = gamma * b1Max;
        if (!(rabi > 0) || !Double.isFinite(rabi)) return FALLBACK_T90_MICROS;
        return ((Math.PI / 2.0) / rabi) * 1e6;
    }

    private static final class BlankStarter implements SequenceStarter {
        @Override public String id() { return "blank"; }
        @Override public String name() { return "Blank"; }
        @Override public String description() { return "Empty timeline with one track per channel."; }
        @Override public ClipSequence build(SimulationConfig config, CircuitDocument circuit, ProjectState state) {
            var tracks = ClipBaker.defaultTracksFor(circuit);
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
    }

    /**
     * Resolve γ from the first continuous-magnetisation substance referenced
     * by a Substance block in the circuit. Throws when no such substance
     * exists — pulse-duration math without a real γ is meaningless and
     * silently defaulting to a proton constant would hide the fact that the
     * starter is targeting the wrong sample (e.g. dropping a CPMG sequence
     * on an NV-only circuit).
     */
    private static double gammaFromCircuitOrThrow(CircuitDocument circuit, ProjectState state) {
        if (circuit == null || state == null) {
            throw new IllegalStateException(
                "Sequence starter requires a circuit + project state to resolve γ from a substance");
        }
        for (var c : circuit.components()) {
            if (c instanceof CircuitComponent.Substance block) {
                var doc = state.substance(block.substanceDocId());
                if (doc != null && doc.substance() instanceof ContinuousMagnetisation cm) {
                    return cm.gammaRadPerSecPerTesla();
                }
            }
        }
        throw new IllegalStateException(
            "Sequence starter could not resolve a ContinuousMagnetisation substance "
            + "in circuit '" + circuit.name() + "' — add one before creating a Carr-Purcell-style sequence");
    }

    private static final class CarrPurcellStarter implements SequenceStarter {
        private final String id;
        private final String name;
        private final String description;
        private final boolean refocusOnQuadrature;
        private CarrPurcellConfigStep step;

        CarrPurcellStarter(String id, String name, String description, boolean refocusOnQuadrature) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.refocusOnQuadrature = refocusOnQuadrature;
        }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public String description() { return description; }

        @Override
        public WizardStep configStep() {
            if (step == null) step = new CarrPurcellConfigStep();
            return step;
        }

        @Override
        public ClipSequence build(SimulationConfig config, CircuitDocument circuit, ProjectState state) {
            int nEchoes = step != null ? step.getEchoCount() : CarrPurcellConfigStep.DEFAULT_ECHO_COUNT;
            double echoSpacingMicros = step != null
                ? step.getEchoSpacingMicros() : CarrPurcellConfigStep.DEFAULT_ECHO_SPACING_MICROS;
            return buildEchoTrain(config, circuit, state, refocusOnQuadrature, nEchoes, echoSpacingMicros);
        }
    }

    private static ClipSequence buildEchoTrain(SimulationConfig config, CircuitDocument circuit, ProjectState state,
                                               boolean refocusOnQuadrature, int nEchoes, double echoSpacingMicros) {
        var tracks = ClipBaker.defaultTracksFor(circuit);
        if (config == null || circuit == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
        // The RF drive is a pair of REAL sources fed into a Modulator. Walk
        // the first Modulator, look up its I and Q source names, and find
        // the tracks that route to them — those are the two timelines a
        // Carr-Purcell sequence drops 90°/180° pulses onto.
        var modulator = firstModulator(circuit);
        if (modulator == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
        var iSrc = CircuitComponent.Modulator.inputSource(modulator, "in0", circuit);
        var qSrc = CircuitComponent.Modulator.inputSource(modulator, "in1", circuit);
        if (iSrc == null || qSrc == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        String iTrackId = trackIdFor(tracks, iSrc.name(), 0);
        String qTrackId = trackIdFor(tracks, qSrc.name(), 0);
        if (iTrackId == null || qTrackId == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        double b1Max = Math.max(Math.abs(iSrc.maxAmplitude()), Math.abs(qSrc.maxAmplitude()));
        // γ comes from the substance the circuit actually targets — no
        // silent proton fallback. A CPMG-style starter on an NV-only
        // circuit throws, which is the right failure: the user should
        // pick a sequence that matches the sample.
        double gamma = gammaFromCircuitOrThrow(circuit, state);
        double t90 = computeT90Micros(gamma, b1Max);
        double t180 = 2 * t90;
        double tau = Math.max(t180, echoSpacingMicros) / 2.0;

        int echoes = Math.max(1, nEchoes);
        var clips = new ArrayList<SignalClip>();
        double cursor = 0;

        clips.add(constantClip(iTrackId, cursor, t90, b1Max));
        cursor += t90;
        cursor += tau;

        String refocusTrackId = refocusOnQuadrature ? qTrackId : iTrackId;
        for (int echo = 0; echo < echoes; echo++) {
            clips.add(constantClip(refocusTrackId, cursor, t180, b1Max));
            cursor += t180;
            cursor += (echo < echoes - 1 ? 2 * tau : tau);
        }

        double total = Math.ceil(cursor);
        double dt = chooseDtMicros(t90);
        return new ClipSequence(dt, total, tracks, clips);
    }

    private static double chooseDtMicros(double t90Micros) {
        double target = t90Micros / 20.0;
        if (target <= 0 || !Double.isFinite(target)) return DEFAULT_DT_MICROS;
        if (target >= 1) return Math.floor(target);
        if (target >= 0.5) return 0.5;
        if (target >= 0.25) return 0.25;
        if (target >= 0.1) return 0.1;
        return target;
    }

    private static CircuitComponent.Modulator firstModulator(CircuitDocument circuit) {
        for (var c : circuit.components()) {
            if (c instanceof CircuitComponent.Modulator m) return m;
        }
        return null;
    }

    private static CircuitComponent.VoltageSource findSourceByName(CircuitDocument circuit, String name) {
        if (name == null) return null;
        for (var src : circuit.voltageSources()) {
            if (name.equals(src.name())) return src;
        }
        return null;
    }

    private static String trackIdFor(List<Track> tracks, String sourceName, int subIndex) {
        for (var t : tracks) {
            var ch = t.simChannel();
            if (sourceName.equals(ch.sourceName()) && ch.subIndex() == subIndex) return t.id();
        }
        return null;
    }

    private static SignalClip constantClip(String trackId, double startMicros, double durationMicros, double amplitude) {
        return new SignalClip(
            null, trackId, new ClipShape.Constant(),
            startMicros, durationMicros, amplitude,
            0, durationMicros, false);
    }

    /**
     * NV Ramsey: pump → π/2 → free precession τ → π/2 → read. Drives the MW
     * I envelope into a single-quadrature π/2 pulse pair and toggles the Laser
     * source for the pump and read windows. The read clicks differ from the
     * pump clicks because the second π/2 projects the accumulated Larmor phase
     * back onto the S_z axis where the optical contrast lives.
     */
    private static final class NvRamseyStarter implements SequenceStarter {
        /** π/2 pulse width — chosen so γ_NV · A_I · t_π/2 = π/2 at A_I = 89 µT. */
        private static final double T_PI_HALF_US = 0.1;     // 100 ns
        private static final double MW_AMPLITUDE_T = 8.9e-5;  // ≈ 89 µT
        private static final double PUMP_US = 3.0;
        private static final double READ_US = 3.0;
        private static final double DARK_US = 0.01;          // 10 ns settling
        private static final double DEFAULT_TAU_US = 1.0;

        @Override public String id() { return "nv-ramsey"; }
        @Override public String name() { return "NV Ramsey"; }
        @Override public String description() {
            return "NV Ramsey magnetometry: pump, 90, free precession tau, 90, read. "
                + "Drives Laser + MW I/Q tracks on the NV-diamond circuit.";
        }

        @Override
        public ClipSequence build(SimulationConfig config, CircuitDocument circuit, ProjectState state) {
            var tracks = ClipBaker.defaultTracksFor(circuit);
            if (circuit == null) {
                return new ClipSequence(0.01, 100.0, tracks, List.of());
            }

            var laserSrc = findSourceByName(circuit, "Laser");
            String laserTrack = laserSrc != null ? trackIdFor(tracks, laserSrc.name(), 0) : null;

            // MW I/Q sources sit behind the MW Modulator. Walk the first Modulator's
            // in0/in1 the way the CP starter does, but bound by name to avoid grabbing
            // a different modulator if the circuit ever has more than one.
            var mwMod = firstModulator(circuit);
            CircuitComponent.VoltageSource mwI = null, mwQ = null;
            if (mwMod != null) {
                mwI = CircuitComponent.Modulator.inputSource(mwMod, "in0", circuit);
                mwQ = CircuitComponent.Modulator.inputSource(mwMod, "in1", circuit);
            }
            String mwITrack = mwI != null ? trackIdFor(tracks, mwI.name(), 0) : null;
            String mwQTrack = mwQ != null ? trackIdFor(tracks, mwQ.name(), 0) : null;

            // If any required track is missing, fall back to a blank timeline so
            // the wizard still produces a valid sequence document.
            if (laserTrack == null || mwITrack == null) {
                return new ClipSequence(0.01, 100.0, tracks, List.of());
            }

            double cursor = 0;
            var clips = new ArrayList<SignalClip>();

            // Pump.
            clips.add(constantClip(laserTrack, cursor, PUMP_US, 1.0));
            cursor += PUMP_US + DARK_US;

            // First π/2 on MW I.
            clips.add(constantClip(mwITrack, cursor, T_PI_HALF_US, MW_AMPLITUDE_T));
            cursor += T_PI_HALF_US;

            // Free precession τ.
            cursor += DEFAULT_TAU_US;

            // Second π/2 on MW I.
            clips.add(constantClip(mwITrack, cursor, T_PI_HALF_US, MW_AMPLITUDE_T));
            cursor += T_PI_HALF_US + DARK_US;

            // Read.
            clips.add(constantClip(laserTrack, cursor, READ_US, 1.0));
            cursor += READ_US;

            double total = Math.max(cursor + 1, 10);
            // 1 ns dt resolves both the MW envelope hand-off and the ~300 ns NV
            // optical-pump constant cleanly. Keep it fine — the timeline is short.
            double dt = 0.001;
            return new ClipSequence(dt, total, tracks, clips);
        }
    }
}
