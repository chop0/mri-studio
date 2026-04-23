package ax.xz.mri.model.sequence;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.DrivePath;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Built-in starter sequences shown in the New-Sequence wizard.
 *
 * <p>Each starter produces a {@link ClipSequence} when given a
 * {@link SimulationConfig}. The UI records only the resulting clips and tracks
 * on the sequence document. Users are free to modify or discard any of them.
 *
 * <h3>CPMG and CP templates</h3>
 * <p>Both templates need a {@link AmplitudeKind#QUADRATURE QUADRATURE} drive
 * path in the config to place RF pulses on; if the config has no RF path the
 * starter emits the default tracks with no clips. The 90 degree pulse
 * duration is computed from the config's gyromagnetic ratio and the RF
 * path's {@link DrivePath#maxAmplitude() maxAmplitude}.
 */
public final class SequenceStarterLibrary {
    private SequenceStarterLibrary() {}

    /** Fallback 90 degree duration (us) if the config has no usable RF gamma or amplitude. */
    static final double FALLBACK_T90_MICROS = 30.0;
    /** Target dt (us) for seeded sequences - small enough to resolve a square pulse. */
    static final double DEFAULT_DT_MICROS = 1.0;

    private static final SequenceStarter BLANK = new BlankStarter();
    private static final SequenceStarter CPMG = new CarrPurcellStarter(
        "cpmg", "CPMG",
        "90 excitation on x, then 180 refocusing pulses on y. Robust T2 measurement.",
        /*refocusOnQuadrature=*/ true);
    private static final SequenceStarter CP = new CarrPurcellStarter(
        "cp", "Carr-Purcell (CP)",
        "90 excitation on x, then 180 refocusing pulses on x. Sensitive to B1 inhomogeneity.",
        /*refocusOnQuadrature=*/ false);

    private static final List<SequenceStarter> STARTERS = List.of(BLANK, CPMG, CP);

    public static List<SequenceStarter> all() {
        return STARTERS;
    }

    public static Optional<SequenceStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static SequenceStarter defaultStarter() {
        return BLANK;
    }

    /** 90 degree rotation duration in microseconds for a square pulse at {@code b1Max}. */
    static double computeT90Micros(double gamma, double b1Max) {
        double rabi = gamma * b1Max;  // rad/s
        if (!(rabi > 0) || !Double.isFinite(rabi)) return FALLBACK_T90_MICROS;
        double t90Seconds = (Math.PI / 2.0) / rabi;
        return t90Seconds * 1e6;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Starter implementations
    // ══════════════════════════════════════════════════════════════════════════

    /** Empty timeline with the default one-track-per-channel layout. */
    private static final class BlankStarter implements SequenceStarter {
        @Override public String id() { return "blank"; }
        @Override public String name() { return "Blank"; }
        @Override public String description() {
            return "Empty timeline with one track per channel.";
        }
        @Override public ClipSequence build(SimulationConfig config) {
            var tracks = ClipBaker.defaultTracksFor(config);
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
    }

    /**
     * Carr-Purcell-family echo train. CPMG places the refocusing pulses on the
     * Q (y) channel; plain CP places them on the same I (x) channel as the
     * excitation. Pulse durations come from the config; pulse count and
     * spacing come from the shared {@link CarrPurcellConfigStep}.
     */
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
        public ClipSequence build(SimulationConfig config) {
            int nEchoes = step != null ? step.getEchoCount() : CarrPurcellConfigStep.DEFAULT_ECHO_COUNT;
            double echoSpacingMicros = step != null
                ? step.getEchoSpacingMicros() : CarrPurcellConfigStep.DEFAULT_ECHO_SPACING_MICROS;
            return buildEchoTrain(config, refocusOnQuadrature, nEchoes, echoSpacingMicros);
        }
    }

    private static ClipSequence buildEchoTrain(
            SimulationConfig config,
            boolean refocusOnQuadrature,
            int nEchoes,
            double echoSpacingMicros) {
        var tracks = ClipBaker.defaultTracksFor(config);
        if (config == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        var rfPath = firstQuadraturePath(config);
        if (rfPath == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        String iTrackId = trackIdFor(tracks, rfPath.name(), 0);
        String qTrackId = trackIdFor(tracks, rfPath.name(), 1);
        if (iTrackId == null || qTrackId == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        double b1Max = Math.abs(rfPath.maxAmplitude());
        double gamma = Math.abs(config.gamma());
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

    /**
     * Sensible dt for a sequence that contains pulses of length {@code t90}.
     * At least 20 samples across the shortest pulse so the baked waveform
     * captures it cleanly, snapped to a tidy value.
     */
    private static double chooseDtMicros(double t90Micros) {
        double target = t90Micros / 20.0;
        if (target <= 0 || !Double.isFinite(target)) return DEFAULT_DT_MICROS;
        if (target >= 1) return Math.floor(target);
        if (target >= 0.5) return 0.5;
        if (target >= 0.25) return 0.25;
        if (target >= 0.1) return 0.1;
        return target;
    }

    private static DrivePath firstQuadraturePath(SimulationConfig config) {
        for (var p : config.drivePaths()) {
            if (p.kind() == AmplitudeKind.QUADRATURE) return p;
        }
        return null;
    }

    private static String trackIdFor(List<Track> tracks, String drivePathName, int subIndex) {
        for (var t : tracks) {
            var ch = t.outputChannel();
            if (drivePathName.equals(ch.drivePathName()) && ch.subIndex() == subIndex) return t.id();
        }
        return null;
    }

    private static SignalClip constantClip(String trackId, double startMicros, double durationMicros, double amplitude) {
        return new SignalClip(
            null, trackId, new ClipShape.Constant(),
            startMicros, durationMicros, amplitude,
            0, durationMicros);
    }
}
