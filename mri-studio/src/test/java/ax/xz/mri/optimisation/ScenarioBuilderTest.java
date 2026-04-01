package ax.xz.mri.optimisation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioBuilderTest {
    private final ScenarioBuilder builder = new ScenarioBuilder();

    @Test
    void buildsFullTrainRequestFromLatestIterationByDefault() {
        var request = builder.buildRequest(
            OptimisationTestSupport.sampleDocument(),
            new ScenarioBuilder.BuildOptions(
                "Full GRAPE",
                null,
                "Java Output",
                ObjectiveMode.FULL_TRAIN,
                0,
                FreeMaskMode.ALL,
                12,
                4,
                1,
                1
            )
        );

        assertFalse(request.problem().sequenceTemplate().periodic());
        assertInstanceOf(IdentityControlParameterisation.class, request.parameterisation());
        assertEquals(ObjectiveMode.FULL_TRAIN, request.problem().objectiveSpec().mode());
        assertArrayEquals(
            PulseParameterCodec.flatten(OptimisationTestSupport.pulseA()),
            PulseParameterCodec.flatten(request.initialSegments()),
            1e-12
        );
    }

    @Test
    void buildsPeriodicRequestWithReducedCycleTemplate() {
        var request = builder.buildRequest(
            OptimisationTestSupport.sampleDocument(),
            new ScenarioBuilder.BuildOptions(
                "Full GRAPE",
                "0",
                "Java Periodic",
                ObjectiveMode.PERIODIC_CYCLE,
                1,
                FreeMaskMode.REFOCUSING_ONLY,
                15,
                5,
                1,
                1
            )
        );

        assertTrue(request.problem().sequenceTemplate().periodic());
        assertEquals(1, request.problem().sequenceTemplate().prefixSegmentCount());
        assertEquals(1, request.problem().sequenceTemplate().cycleSegmentCount());
        assertEquals(1, request.problem().sequenceTemplate().cycleRepeatCount());
        assertInstanceOf(PeriodicCycleParameterisation.class, request.parameterisation());
        for (int index = 0; index < 10; index++) assertFalse(request.freeMask()[index]);
        for (int index = 10; index < request.freeMask().length; index++) assertTrue(request.freeMask()[index]);
    }

    @Test
    void rejectsUnknownScenarioName() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildRequest(
            OptimisationTestSupport.sampleDocument(),
            new ScenarioBuilder.BuildOptions(
                "Missing",
                null,
                "Out",
                ObjectiveMode.FULL_TRAIN,
                0,
                FreeMaskMode.ALL,
                10,
                2,
                1,
                1
            )
        ));
    }
}
