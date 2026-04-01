package ax.xz.mri.optimisation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PeriodicCycleParameterisationTest {
    @Test
    void expandsPrefixAndRepeatedCycleSegments() {
        var reduced = OptimisationTestSupport.pulseA();
        var template = SequenceTemplate.periodicCycle(
            OptimisationTestSupport.finiteTemplateFor(reduced).segments(),
            1,
            1,
            3
        );
        var parameterisation = new PeriodicCycleParameterisation(template);

        var expanded = parameterisation.expandSegments(reduced);

        assertEquals(4, expanded.size());
        assertArrayEquals(
            PulseParameterCodec.flatten(reduced.subList(0, 1)),
            PulseParameterCodec.flatten(expanded.subList(0, 1)),
            1e-12
        );
        for (int index = 1; index < expanded.size(); index++) {
            assertArrayEquals(
                PulseParameterCodec.flatten(reduced.subList(1, 2)),
                PulseParameterCodec.flatten(expanded.subList(index, index + 1)),
                1e-12
            );
        }
        for (int index = 2; index < expanded.size(); index++) {
            assertNotSame(expanded.get(1), expanded.get(index));
        }
    }
}
