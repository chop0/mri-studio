package ax.xz.mri.optimisation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PulseParameterCodecTest {
    @Test
    void flattenAndSplitRoundTripPreservesControls() {
        var pulse = OptimisationTestSupport.pulseA();
        var template = OptimisationTestSupport.finiteTemplateFor(pulse);

        double[] flat = PulseParameterCodec.flatten(pulse);
        var rebuilt = PulseParameterCodec.split(flat, template);

        assertArrayEquals(flat, PulseParameterCodec.flatten(rebuilt), 1e-12);
    }

    @Test
    void defaultMasksRespectRequestedFreedomMode() {
        var template = OptimisationTestSupport.finiteTemplateFor(OptimisationTestSupport.pulseA());

        boolean[] allMask = PulseParameterCodec.defaultFreeMask(template, FreeMaskMode.ALL);
        boolean[] refocusingMask = PulseParameterCodec.defaultFreeMask(template, FreeMaskMode.REFOCUSING_ONLY);
        boolean[] noneMask = PulseParameterCodec.defaultFreeMask(template, FreeMaskMode.NONE);

        assertEquals(template.flattenedLength(), allMask.length);
        for (boolean value : allMask) assertTrue(value);
        for (int index = 0; index < 10; index++) assertFalse(refocusingMask[index]);
        for (int index = 10; index < refocusingMask.length; index++) assertTrue(refocusingMask[index]);
        for (boolean value : noneMask) assertFalse(value);
    }

    @Test
    void defaultBoundsApplyPerChannelBoundsAndBinaryGateRange() {
        var template = OptimisationTestSupport.finiteTemplateFor(OptimisationTestSupport.pulseA());

        double[][] channelBounds = {
            {-OptimisationHardwareLimits.B1_MAX, OptimisationHardwareLimits.B1_MAX},
            {-OptimisationHardwareLimits.B1_MAX, OptimisationHardwareLimits.B1_MAX},
            {-OptimisationHardwareLimits.GX_MAX, OptimisationHardwareLimits.GX_MAX},
            {-OptimisationHardwareLimits.GZ_MAX, OptimisationHardwareLimits.GZ_MAX}
        };
        double[] lower = PulseParameterCodec.defaultLowerBounds(template, channelBounds);
        double[] upper = PulseParameterCodec.defaultUpperBounds(template, channelBounds);

        assertEquals(template.flattenedLength(), lower.length);
        assertEquals(-OptimisationHardwareLimits.B1_MAX, lower[0], 0.0);
        assertEquals(OptimisationHardwareLimits.B1_MAX, upper[0], 0.0);
        assertEquals(-OptimisationHardwareLimits.GX_MAX, lower[2], 0.0);
        assertEquals(OptimisationHardwareLimits.GX_MAX, upper[2], 0.0);
        assertEquals(-OptimisationHardwareLimits.GZ_MAX, lower[3], 0.0);
        assertEquals(OptimisationHardwareLimits.GZ_MAX, upper[3], 0.0);
        assertEquals(0.0, lower[4], 0.0);
        assertEquals(1.0, upper[4], 0.0);
    }

    @Test
    void copySegmentsProducesIndependentSegmentObjects() {
        var pulse = OptimisationTestSupport.pulseA();
        var copy = PulseParameterCodec.copySegments(pulse);

        assertNotSame(pulse.get(0), copy.get(0));
        assertArrayEquals(PulseParameterCodec.flatten(pulse), PulseParameterCodec.flatten(copy), 1e-12);
    }
}
