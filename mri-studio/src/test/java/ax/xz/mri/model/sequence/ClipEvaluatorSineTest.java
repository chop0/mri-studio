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
    private static final SequenceChannel CH = new SequenceChannel("B1", 0);

    /** Helper: build a sine clip starting at t=0, over [0,duration] μs, with the given shape. */
    private static SignalClip sine(double duration, double amplitude, ClipShape.Sine shape) {
        return new SignalClip(
            "test", CH, shape,
            0.0,            // startTime
            duration,       // duration (μs)
            amplitude,
            0.0,            // mediaOffset
            duration        // mediaDuration
        );
    }

    // ---------- cycles-based parameterisation ----------

    @Test
    void cyclesOne_givesOneFullCycleAcrossClip() {
        // cycles=1 → phase argument goes from 0 to 2π across [0, duration].
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));

        // u = (t / mediaDuration) so at t = 0, 250, 500, 750, 1000 μs we expect the
        // canonical sine pattern 0, +1, 0, -1, 0.
        assertEquals( 0.0, clip.evaluate(  0.0), TOL);
        assertEquals( 1.0, clip.evaluate(250.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
        assertEquals(-1.0, clip.evaluate(750.0), TOL);
    }

    @Test
    void cyclesTwo_givesTwoFullCyclesAcrossClip() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 2.0));

        // cycles=2: at u=0.25 we should be at sin(π) = 0, at u=0.5 at sin(2π) = 0.
        // Quarter-cycle peaks happen at u = 1/8, 3/8, 5/8, 7/8.
        assertEquals( 1.0, clip.evaluate(125.0), TOL);
        assertEquals( 0.0, clip.evaluate(250.0), TOL);
        assertEquals(-1.0, clip.evaluate(375.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
    }

    @Test
    void phaseOffsetShiftsWaveform() {
        // phase = π/2 → sin(π/2 + 0) = 1 at clip start.
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
        // 1000 Hz over a 1000 μs (1 ms) clip = exactly 1 cycle. Cross-checks the
        // cycles=0 branch against the cycles>0 branch.
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000.0, 0.0, 0.0));

        assertEquals( 0.0, clip.evaluate(  0.0), TOL);
        assertEquals( 1.0, clip.evaluate(250.0), TOL);
        assertEquals( 0.0, clip.evaluate(500.0), TOL);
        assertEquals(-1.0, clip.evaluate(750.0), TOL);
    }

    @Test
    void frequencyHz_respectsActualDuration() {
        // 2000 Hz over a 1000 μs clip = 2 cycles.
        var clip = sine(1000, 1.0, new ClipShape.Sine(2000.0, 0.0, 0.0));
        assertEquals( 1.0, clip.evaluate(125.0), TOL); // quarter of first cycle
        assertEquals(-1.0, clip.evaluate(375.0), TOL); // 3/4 through first cycle
        assertEquals( 1.0, clip.evaluate(625.0), TOL); // quarter through second cycle
    }

    @Test
    void defaultSineBehavesLikeOneCycleFor1msClip() {
        // Canonical defaults: frequencyHz=1000, phase=0, cycles=0 → 1000 Hz × 1e-3 s
        // = one full cycle. Sine returns to 0 halfway.
        var clip = sine(1000, 1.0, ClipShape.Sine.defaults());
        assertEquals(0.0, clip.evaluate(500.0), TOL);
    }

    // ---------- Boundary / integration behaviour ----------

    @Test
    void outsideClipRangeReturnsZero() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, Math.PI / 2, 1.0));
        // Before start
        assertEquals(0.0, clip.evaluate(  -1.0), TOL);
        // At/after end (half-open: [start, end))
        assertEquals(0.0, clip.evaluate(1000.0), TOL);
        assertEquals(0.0, clip.evaluate(5000.0), TOL);
    }

    @Test
    void clipOffsetOnTimelineIsRespected() {
        // Start the same sine at t=500 μs. Quarter-cycle peak is at t=500+250=750.
        var clip = new SignalClip(
            "test", CH, new ClipShape.Sine(1000, 0.0, 1.0),
            500.0,   // startTime
            1000.0,  // duration
            1.0,     // amplitude
            0.0,     // mediaOffset
            1000.0   // mediaDuration
        );
        assertEquals( 0.0, clip.evaluate( 500.0), TOL);
        assertEquals( 1.0, clip.evaluate( 750.0), TOL);
        assertEquals(-1.0, clip.evaluate(1250.0), TOL);
    }

    @Test
    void negativeCyclesTreatedAsZero_fallsBackToFrequencyHz() {
        // The cycles>0 branch only fires for strictly positive values. Negative
        // (likely garbage) cycles should route through frequencyHz.
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000.0, 0.0, -5.0));
        assertEquals(1.0, clip.evaluate(250.0), TOL);
    }

    @Test
    void channelSumIncludesSineClip() {
        var clip = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        double v = ClipEvaluator.evaluateChannel(List.of(clip), CH, 250.0);
        assertEquals(1.0, v, TOL);
    }

    @Test
    void overlappingSineClipsAddConstructively() {
        var a = sine(1000, 1.0, new ClipShape.Sine(1000, 0.0, 1.0));
        var b = sine(1000, 0.5, new ClipShape.Sine(1000, 0.0, 1.0));
        double v = ClipEvaluator.evaluateChannel(List.of(a, b), CH, 250.0);
        assertEquals(1.5, v, TOL);
    }

    @Test
    void sineIsBoundedByAmplitude() {
        // Sample the entire clip at fine resolution; |output| should never exceed
        // the amplitude. Catches any accidental gain factor in the shape function.
        var clip = sine(1000, 3.0, new ClipShape.Sine(1000, 0.3, 7.0));
        for (int i = 0; i < 1000; i++) {
            double t = i * 0.999; // strictly inside the half-open interval
            double v = clip.evaluate(t);
            assertTrue(Math.abs(v) <= 3.0 + TOL,
                "Sample at t=" + t + " was " + v + " (exceeds amplitude 3.0)");
        }
    }
}
