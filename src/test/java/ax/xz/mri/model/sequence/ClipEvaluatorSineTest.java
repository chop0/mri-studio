package ax.xz.mri.model.sequence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the sine-wave evaluation path on {@link ClipShape.Sine}.
 *
 * <p>The sine shape supports two mutually-exclusive parameterisations:
 * <ul>
 *   <li><b>cycles &gt; 0</b>: exactly N cycles over the media extent, independent of duration.</li>
 *   <li><b>cycles == 0</b>: {@code frequencyHz} controls the real-world frequency; the
 *       number of cycles scales with the clip's media duration (in μs).</li>
 * </ul>
 */
class ClipEvaluatorSineTest {
    private static final double TOL = 1e-9;
    private static final SequenceChannel CHANNEL = new SequenceChannel("B1", 0);
    private static final Track TRACK = new Track("track-b1-i", CHANNEL, "B1 I");

    /** Helper: build a sine clip starting at t=0, over [0,duration] μs, with the given shape. */
    private static SignalClip sine(double duration, double amplitude, ClipShape.Sine shape) {
        return new SignalClip(
            "test", TRACK.id(), shape,
            0.0,
            duration,
            amplitude,
            0.0,
            duration
        );
    }

    // ---------- cycles-based parameterisation ----------

    @Test
    void cyclesOne_givesOneFullCycleAcrossClip() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        assertEquals( 0.0, clip.evaluate(  0.0), TOL);
        assertEquals( 1.0, clip.evaluate(250.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
        assertEquals(-1.0, clip.evaluate(750.0), TOL);
    }

    @Test
    void cyclesTwo_givesTwoFullCyclesAcrossClip() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 2.0));
        assertEquals( 1.0, clip.evaluate(125.0), TOL);
        assertEquals( 0.0, clip.evaluate(250.0), TOL);
        assertEquals(-1.0, clip.evaluate(375.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
    }

    @Test
    void phaseOffsetShiftsWaveform() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, Math.PI / 2, 1.0));
        assertEquals(1.0, clip.evaluate(  0.0), TOL);
        assertEquals(0.0, clip.evaluate(250.0), TOL);
    }

    @Test
    void amplitudeScalesPeak() {
        var clip = sine(1000, 2.5, new ClipShape.Sine(1000, 0.0, 1.0));
        assertEquals( 2.5, clip.evaluate(250.0), TOL);
        assertEquals(-2.5, clip.evaluate(750.0), TOL);
    }

    // ---------- frequencyHz-based parameterisation ----------

    @Test
    void frequencyHz_scalesWithDuration() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000.0, 0.0, 0.0));
        assertEquals( 0.0, clip.evaluate(  0.0), TOL);
        assertEquals( 1.0, clip.evaluate(250.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
        assertEquals(-1.0, clip.evaluate(750.0), TOL);
    }

    @Test
    void frequencyHz_respectsActualDuration() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(2000.0, 0.0, 0.0));
        assertEquals( 1.0, clip.evaluate(125.0), TOL);
        assertEquals(-1.0, clip.evaluate(375.0), TOL);
        assertEquals( 1.0, clip.evaluate(625.0), TOL);
    }

    @Test
    void defaultSineBehavesLikeOneCycleFor1msClip() {
        var clip = sine(1000, 1.0, ClipShape.Sine.defaults());
        assertEquals(0.0, clip.evaluate(500.0), TOL);
    }

    // ---------- Boundary / integration behaviour ----------

    @Test
    void outsideClipRangeReturnsZero() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, Math.PI / 2, 1.0));
        assertEquals(0.0, clip.evaluate(  -1.0), TOL);
        assertEquals(0.0, clip.evaluate(1000.0), TOL);
        assertEquals(0.0, clip.evaluate(5000.0), TOL);
    }

    @Test
    void clipOffsetOnTimelineIsRespected() {
        var clip = new SignalClip(
            "test", TRACK.id(), new ClipShape.Sine(1000, 0.0, 1.0),
            500.0, 1000.0, 1.0, 0.0, 1000.0);
        assertEquals( 0.0, clip.evaluate( 500.0), TOL);
        assertEquals( 1.0, clip.evaluate( 750.0), TOL);
        assertEquals(-1.0, clip.evaluate(1250.0), TOL);
    }

    @Test
    void negativeCyclesTreatedAsZero_fallsBackToFrequencyHz() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000.0, 0.0, -5.0));
        assertEquals(1.0, clip.evaluate(250.0), TOL);
    }

    @Test
    void channelSumIncludesSineClip() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        double v = ClipEvaluator.evaluateChannel(List.of(clip), List.of(TRACK), CHANNEL, 250.0);
        assertEquals(1.0, v, TOL);
    }

    @Test
    void overlappingSineClipsAddConstructively() {
        var a = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        var b = sine(1000, 0.5, new ClipShape.Sine(1000, 0.0, 1.0));
        double v = ClipEvaluator.evaluateChannel(List.of(a, b), List.of(TRACK), CHANNEL, 250.0);
        assertEquals(1.5, v, TOL);
    }

    @Test
    void multipleTracksTargetingSameOutputSum() {
        // Two tracks both routing to CHANNEL — clips on either contribute to
        // the same channel sum.
        var track2 = new Track("track-b1-other", CHANNEL, "B1 extra");
        var a = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        var b = new SignalClip("b", track2.id(), new ClipShape.Sine(1000, 0.0, 1.0),
            0.0, 1000.0, 0.5, 0.0, 1000.0);
        double v = ClipEvaluator.evaluateChannel(List.of(a, b), List.of(TRACK, track2), CHANNEL, 250.0);
        assertEquals(1.5, v, TOL);
    }

    @Test
    void sineIsBoundedByAmplitude() {
        var clip = sine(1000, 3.0, new ClipShape.Sine(1000, 0.3, 7.0));
        for (int i = 0; i < 1000; i++) {
            double t = i * 0.999;
            double v = clip.evaluate(t);
            assertTrue(Math.abs(v) <= 3.0 + TOL,
                "Sample at t=" + t + " was " + v + " (exceeds amplitude 3.0)");
        }
    }
}
