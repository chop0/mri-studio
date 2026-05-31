package ax.xz.mri.ui.sim;

import ax.xz.mri.ui.time.Generation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTest {
    @Test
    void freshGenerationIsCurrent() {
        var gen = new Generation();

        assertTrue(gen.isCurrent(gen.current()));
    }

    @Test
    void bumpInvalidatesPreviousToken() {
        var gen = new Generation();
        long captured = gen.current();

        gen.bump();

        assertFalse(gen.isCurrent(captured));
    }

    @Test
    void bumpReturnsTheNewValueMonotonically() {
        var gen = new Generation();
        long first = gen.bump();
        long second = gen.bump();

        assertTrue(second > first);
        assertNotEquals(first, second);
    }
}
