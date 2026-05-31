package ax.xz.mri.ui.timeline.element.clip;

import ax.xz.mri.model.sequence.SignalClip;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Paints a clip's sampled waveform onto a Canvas.
 *
 * <p>Pure function — given a {@link SignalClip}, a width, a height, an
 * amplitude scale, and a stroke colour, the waveform is sampled and stroked.
 * Sampling is delegated to {@link WaveformCache} so dragging a clip doesn't
 * re-sample its shape on every frame; the cache key is the clip's hashCode,
 * so any field change invalidates automatically.
 */
public final class WaveformPainter {
    public static final int MAX_SAMPLES = 320;

    private WaveformPainter() {}

    /**
     * Paint a clip's waveform centred on the canvas's vertical midline, scaled
     * so {@code displayMax} fills the visible half-height.
     *
     * @param g           target graphics
     * @param cache       sample cache (one per timeline; safe to share)
     * @param clip        clip to render
     * @param width       canvas width in pixels
     * @param height      canvas height in pixels
     * @param displayMax  amplitude that maps to half-height (i.e. {@code height/2})
     * @param stroke      line colour
     */
    public static void paint(GraphicsContext g, WaveformCache cache, SignalClip clip,
                             double width, double height, double displayMax, Color stroke) {
        if (width <= 0 || height <= 0 || clip.duration() <= 0) return;
        int samples = (int) Math.max(2, Math.min(width, MAX_SAMPLES));
        double[] values = cache.getOrCompute(clip, samples);

        double midY = height * 0.5;
        double scale = displayMax > 0 ? midY / displayMax : 0;

        g.setStroke(stroke);
        g.setLineWidth(1.2);
        g.beginPath();
        for (int i = 0; i <= samples; i++) {
            double x = ((double) i / samples) * width;
            double y = midY - values[i] * scale;
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
    }
}
