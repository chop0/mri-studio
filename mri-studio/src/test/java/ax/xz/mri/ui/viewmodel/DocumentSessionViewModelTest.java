package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DocumentSessionViewModelTest {
    @Test
    void setDocumentSelectsAlphabeticalScenarioAndLatestNumericIteration() {
        var session = new DocumentSessionViewModel();
        var document = TestBlochDataFactory.sampleDocument();

        session.setDocument(new File("bloch_data.json"), document);

        assertEquals(List.of("Baseline", "Full GRAPE"), List.copyOf(session.scenarioKeys));
        assertEquals("Baseline", session.currentScenario.get());
        assertEquals(List.of("1", "2", "10"), List.copyOf(session.iterationKeys));
        assertEquals(2, session.iterationIndex.get());
        assertSame(document.scenarios().get("Baseline").pulses().get("10"), session.currentPulse.get());
    }

    @Test
    void switchingScenarioRefreshesIterationsAndClearResetsEverything() {
        var session = new DocumentSessionViewModel();
        var document = TestBlochDataFactory.sampleDocument();

        session.setDocument(new File("bloch_data.json"), document);
        session.currentScenario.set("Full GRAPE");

        assertEquals(List.of("0", "3"), List.copyOf(session.iterationKeys));
        assertEquals(1, session.iterationIndex.get());
        assertSame(document.scenarios().get("Full GRAPE").pulses().get("3"), session.currentPulse.get());

        session.clearDocument();

        assertNull(session.currentFile.get());
        assertNull(session.blochData.get());
        assertNull(session.currentScenario.get());
        assertNull(session.currentPulse.get());
        assertEquals(List.of(), List.copyOf(session.scenarioKeys));
        assertEquals(List.of(), List.copyOf(session.iterationKeys));
        assertEquals(0, session.iterationIndex.get());
    }
}
