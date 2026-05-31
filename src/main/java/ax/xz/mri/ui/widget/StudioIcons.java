package ax.xz.mri.ui.widget;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.kordamp.ikonli.javafx.FontIcon;

public final class StudioIcons {
    private StudioIcons() {}

    public static final int SIZE_SMALL   = 16;
    public static final int SIZE_REGULAR = 18;

    public enum Kind {
        SAVE, UNDO, REDO,
        ZOOM_IN, ZOOM_OUT, ZOOM_FIT,
        SNAP, OUTPUTS, PLAY, RECORD, AUTO_RUN,
        SELECT, SINE, SINC, TRAPEZOID, GAUSSIAN, TRIANGLE, CONSTANT, SPLINE,
        CHEVRON_DOWN, CHEVRON_RIGHT, CHEVRON_UP, CHEVRON_LEFT,
        CLOSE, DOTS, FILTER, EYE,
        TRACK_MUTE, TRACK_SOLO, TRACK_RECORD,
        EXPLORER, INSPECTOR, MESSAGES, POINTS,
        SEQUENCE, SIM_BOX, HARDWARE_BOX,
    }

    public static Node of(Kind kind) { return of(kind, SIZE_REGULAR); }

    public static Node of(Kind kind, int sizePx) {
        var spec = waveformSvg(kind);
        if (spec != null) return waveformIcon(spec.path, spec.filled, sizePx);
        var icon = new FontIcon(carbonFor(kind));
        icon.setIconSize(sizePx);
        icon.getStyleClass().add("studio-icon");
        return icon;
    }

    private static Node waveformIcon(String path, boolean filled, int sizePx) {
        var p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("studio-icon-glyph");
        if (filled) p.getStyleClass().add("filled");
        var box = new StackPane(new Group(p));
        box.setMinSize(sizePx, sizePx);
        box.setPrefSize(sizePx, sizePx);
        box.setMaxSize(sizePx, sizePx);
        box.getStyleClass().addAll("studio-icon", "studio-icon-vector");
        return box;
    }

    private record IconSpec(String path, boolean filled) {}

    private static IconSpec waveformSvg(Kind kind) {
        return switch (kind) {
            case SELECT    -> new IconSpec("M5 3 L5 14 L8 11 L10 14 L11 13 L9 10 L13 10 Z", true);
            case SINE      -> new IconSpec(sineWave(20), false);
            case SINC      -> new IconSpec(sincWave(20), false);
            case TRAPEZOID -> new IconSpec("M2 16 L6 4 L14 4 L18 16", false);
            case GAUSSIAN  -> new IconSpec(gaussianWave(20), false);
            case TRIANGLE  -> new IconSpec("M2 16 L6 16 L10 4 L14 16 L18 16", false);
            case CONSTANT  -> new IconSpec("M2 6 L18 6 L18 16 L2 16 Z", true);
            case SPLINE    -> new IconSpec(splineWave(20), false);
            default -> null;
        };
    }

    private static String sineWave(int s) {
        return polylineCurve(s, 32, t -> 0.5 - 0.45 * Math.sin(2 * Math.PI * t));
    }

    private static String sincWave(int s) {
        return polylineCurve(s, 64, t -> {
            double x = (t - 0.5) * 8;
            double v = Math.abs(x) < 1e-6 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
            return 0.5 - 0.45 * v;
        });
    }

    private static String gaussianWave(int s) {
        return polylineCurve(s, 32, t -> {
            double x = (t - 0.5) * 5;
            return 0.95 - 0.85 * Math.exp(-0.5 * x * x);
        });
    }

    private static String splineWave(int s) {
        return polylineCurve(s, 32, t -> 0.5 - 0.4 * Math.tanh(6 * (t - 0.5)));
    }

    /** Build an SVG polyline string from f(t∈[0,1]) → y∈[0,1] mapped to a sizePx-wide box with 2px padding. */
    private static String polylineCurve(int sizePx, int samples, java.util.function.DoubleUnaryOperator f) {
        double pad = 2, w = sizePx - 2 * pad, h = sizePx - 2 * pad;
        var sb = new StringBuilder();
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = pad + t * w;
            double y = pad + f.applyAsDouble(t) * h;
            sb.append(i == 0 ? 'M' : 'L').append(' ')
              .append(String.format(java.util.Locale.ROOT, "%.2f", x)).append(' ')
              .append(String.format(java.util.Locale.ROOT, "%.2f", y)).append(' ');
        }
        return sb.toString();
    }

    private static Ikon carbonFor(Kind kind) {
        return switch (kind) {
            case SAVE        -> CarbonIcons.SAVE;
            case UNDO        -> CarbonIcons.UNDO;
            case REDO        -> CarbonIcons.REDO;
            case ZOOM_IN     -> CarbonIcons.ZOOM_IN;
            case ZOOM_OUT    -> CarbonIcons.ZOOM_OUT;
            case ZOOM_FIT    -> CarbonIcons.FIT_TO_SCREEN;
            case SNAP        -> CarbonIcons.GRID;
            case OUTPUTS     -> CarbonIcons.LIST_CHECKED;
            case PLAY        -> CarbonIcons.PLAY;
            case RECORD      -> CarbonIcons.RECORDING_FILLED;
            case AUTO_RUN    -> CarbonIcons.RENEW;
            case CHEVRON_DOWN  -> CarbonIcons.CHEVRON_DOWN;
            case CHEVRON_RIGHT -> CarbonIcons.CHEVRON_RIGHT;
            case CHEVRON_UP    -> CarbonIcons.CHEVRON_UP;
            case CHEVRON_LEFT  -> CarbonIcons.CHEVRON_LEFT;
            case CLOSE        -> CarbonIcons.CLOSE;
            case DOTS         -> CarbonIcons.OVERFLOW_MENU_HORIZONTAL;
            case FILTER       -> CarbonIcons.FILTER;
            case EYE          -> CarbonIcons.VIEW;
            case TRACK_MUTE   -> CarbonIcons.VOLUME_MUTE;
            case TRACK_SOLO   -> CarbonIcons.HEADPHONES;
            case TRACK_RECORD -> CarbonIcons.RECORDING_FILLED;
            case EXPLORER     -> CarbonIcons.FOLDER;
            case INSPECTOR    -> CarbonIcons.SETTINGS;
            case MESSAGES     -> CarbonIcons.NOTIFICATION;
            case POINTS       -> CarbonIcons.LOCATION;
            case SEQUENCE     -> CarbonIcons.SCRIPT;
            case SIM_BOX      -> CarbonIcons.MACHINE_LEARNING_MODEL;
            case HARDWARE_BOX -> CarbonIcons.MICROSCOPE;
            default -> throw new IllegalStateException("non-FontIcon kind " + kind);
        };
    }
}
