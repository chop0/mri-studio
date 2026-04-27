package ax.xz.mri.service.simulation;

import ax.xz.mri.support.TestSimulationOutputFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseMapComputerTest {
    @Test
    void phaseMapsIncludeSignalProjectionAlongsideExcitation() {
        var phaseMap = PhaseMapComputer.computePhaseZ(
            TestSimulationOutputFactory.incoherentTransverseDocument(),
            TestSimulationOutputFactory.freePrecessionPulse()
        );

        boolean foundDifferentSignalProjection = false;
        for (var row : phaseMap.data()) {
            for (var cell : row) {
                assertTrue(cell.signalProjection() >= 0);
                assertTrue(cell.signalProjection() <= cell.mPerp() + 1e-9);
                if (Math.abs(cell.signalProjection() - cell.mPerp()) > 1e-4) {
                    foundDifferentSignalProjection = true;
                }
            }
        }

        assertTrue(foundDifferentSignalProjection);
    }
}
