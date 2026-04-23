package ax.xz.mri.model.sequence;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimulationConfig;
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

    private static final List<SequenceStarter> STARTERS = List.of(BLANK, CPMG, CP);

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
        @Override public ClipSequence build(SimulationConfig config, CircuitDocument circuit) {
            var tracks = ClipBaker.defaultTracksFor(circuit);
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
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
        public ClipSequence build(SimulationConfig config, CircuitDocument circuit) {
            int nEchoes = step != null ? step.getEchoCount() : CarrPurcellConfigStep.DEFAULT_ECHO_COUNT;
            double echoSpacingMicros = step != null
                ? step.getEchoSpacingMicros() : CarrPurcellConfigStep.DEFAULT_ECHO_SPACING_MICROS;
            return buildEchoTrain(config, circuit, refocusOnQuadrature, nEchoes, echoSpacingMicros);
        }
    }

    private static ClipSequence buildEchoTrain(SimulationConfig config, CircuitDocument circuit,
                                               boolean refocusOnQuadrature, int nEchoes, double echoSpacingMicros) {
        var tracks = ClipBaker.defaultTracksFor(circuit);
        if (config == null || circuit == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }
        var rfSrc = firstQuadratureSource(circuit);
        if (rfSrc == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        String iTrackId = trackIdFor(tracks, rfSrc.name(), 0);
        String qTrackId = trackIdFor(tracks, rfSrc.name(), 1);
        if (iTrackId == null || qTrackId == null) {
            return new ClipSequence(DEFAULT_DT_MICROS * 10, 1000.0, tracks, List.of());
        }

        double b1Max = Math.abs(rfSrc.maxAmplitude());
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

    private static double chooseDtMicros(double t90Micros) {
        double target = t90Micros / 20.0;
        if (target <= 0 || !Double.isFinite(target)) return DEFAULT_DT_MICROS;
        if (target >= 1) return Math.floor(target);
        if (target >= 0.5) return 0.5;
        if (target >= 0.25) return 0.25;
        if (target >= 0.1) return 0.1;
        return target;
    }

    private static CircuitComponent.VoltageSource firstQuadratureSource(CircuitDocument circuit) {
        for (var src : circuit.voltageSources()) {
            if (src.kind() == AmplitudeKind.QUADRATURE) return src;
        }
        return null;
    }

    private static String trackIdFor(List<Track> tracks, String sourceName, int subIndex) {
        for (var t : tracks) {
            var ch = t.outputChannel();
            if (sourceName.equals(ch.sourceName()) && ch.subIndex() == subIndex) return t.id();
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
