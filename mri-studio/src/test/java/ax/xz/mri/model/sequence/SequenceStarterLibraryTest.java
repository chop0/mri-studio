package ax.xz.mri.model.sequence;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the built-in sequence starter templates.
 *
 * <p>Starters must tolerate every circuit shape — including circuits that lack
 * an RF (QUADRATURE) source. They must compute pulse durations that match the
 * Rabi rate implied by the config's {@code gamma} and the source's
 * {@code maxAmplitude}, and they must place clips only on tracks whose channel
 * ids actually exist in the produced track list.
 */
class SequenceStarterLibraryTest {

    private static final double GAMMA = 267.522e6;
    private static final double B1_MAX = 200e-6;
    private static final ProjectNodeId CIRCUIT_ID = new ProjectNodeId("circuit-test");

    private static SimulationConfig config() {
        return new SimulationConfig(
            1000, 100, GAMMA, 5, 40, 20, 5, 5, 1.5, 1e-6, CIRCUIT_ID);
    }

    private static CircuitDocument circuitWithRf() {
        var rfSrc = new CircuitComponent.VoltageSource(new ComponentId("src-rf"),
            "RF", AmplitudeKind.QUADRATURE, 63e6, 0, B1_MAX, 0);
        var gxSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gx"),
            "Gradient X", AmplitudeKind.REAL, 0, -0.030, 0.030, 0);
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"),
            "RF Coil", new ProjectNodeId("ef-rf"), 0, 0);
        var gxCoil = new CircuitComponent.Coil(new ComponentId("coil-gx"),
            "Gx Coil", new ProjectNodeId("ef-gx"), 0, 0);
        var wires = List.of(
            new Wire("w-rf-out", new ComponentTerminal(rfSrc.id(), "out"), new ComponentTerminal(rfCoil.id(), "in")),
            new Wire("w-gx-out", new ComponentTerminal(gxSrc.id(), "out"), new ComponentTerminal(gxCoil.id(), "in"))
        );
        return new CircuitDocument(CIRCUIT_ID, "Test Circuit",
            List.of(rfSrc, gxSrc, rfCoil, gxCoil), wires, CircuitLayout.empty());
    }

    private static CircuitDocument circuitWithoutRf() {
        var gxSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gx"),
            "Gradient X", AmplitudeKind.REAL, 0, -0.030, 0.030, 0);
        var gxCoil = new CircuitComponent.Coil(new ComponentId("coil-gx"),
            "Gx Coil", new ProjectNodeId("ef-gx"), 0, 0);
        var wires = List.of(
            new Wire("w-gx-out", new ComponentTerminal(gxSrc.id(), "out"), new ComponentTerminal(gxCoil.id(), "in"))
        );
        return new CircuitDocument(CIRCUIT_ID, "No-RF Circuit",
            List.of(gxSrc, gxCoil), wires, CircuitLayout.empty());
    }

    private static SequenceStarter starter(String id) {
        return SequenceStarterLibrary.byId(id).orElseThrow();
    }

    @Test
    void library_exposesBlank_cpmg_cp_inOrder() {
        var all = SequenceStarterLibrary.all();
        assertEquals(3, all.size());
        assertEquals("blank", all.get(0).id());
        assertEquals("cpmg", all.get(1).id());
        assertEquals("cp", all.get(2).id());
    }

    @Test
    void defaultStarterIsBlank() {
        assertEquals("blank", SequenceStarterLibrary.defaultStarter().id());
    }

    @Test
    void blank_producesTracksForEveryChannelAndNoClips() {
        var seq = starter("blank").build(config(), circuitWithRf());
        // RF has 2 (I, Q) + Gradient X has 1 = 3 tracks.
        assertEquals(3, seq.tracks().size());
        assertTrue(seq.clips().isEmpty());
    }

    @Test
    void blank_acceptsNullCircuit() {
        var seq = starter("blank").build(config(), null);
        assertNotNull(seq);
        assertTrue(seq.tracks().isEmpty());
        assertTrue(seq.clips().isEmpty());
    }

    @Test
    void cpmg_placesExcitationOnI_andRefocusingOnQ() {
        var seq = starter("cpmg").build(config(), circuitWithRf());
        assertEquals(3, seq.tracks().size());
        // 1 excitation + 4 refocus pulses (default echo count) = 5 clips.
        assertEquals(5, seq.clips().size());

        Track iTrack = seq.tracks().stream()
            .filter(t -> t.outputChannel().sourceName().equals("RF") && t.outputChannel().subIndex() == 0)
            .findFirst().orElseThrow();
        Track qTrack = seq.tracks().stream()
            .filter(t -> t.outputChannel().sourceName().equals("RF") && t.outputChannel().subIndex() == 1)
            .findFirst().orElseThrow();

        var sorted = seq.clips().stream()
            .sorted((a, b) -> Double.compare(a.startTime(), b.startTime()))
            .toList();

        assertEquals(iTrack.id(), sorted.get(0).trackId(), "Excitation must be on the I (x) track");
        for (int i = 1; i < sorted.size(); i++) {
            assertEquals(qTrack.id(), sorted.get(i).trackId(),
                "CPMG refocus pulse " + i + " must be on the Q (y) track");
        }
    }

    @Test
    void cp_placesAllPulsesOnI() {
        var seq = starter("cp").build(config(), circuitWithRf());
        Track iTrack = seq.tracks().stream()
            .filter(t -> t.outputChannel().sourceName().equals("RF") && t.outputChannel().subIndex() == 0)
            .findFirst().orElseThrow();
        for (var clip : seq.clips()) {
            assertEquals(iTrack.id(), clip.trackId(),
                "Plain Carr-Purcell keeps every pulse on the x (I) axis");
        }
    }

    @Test
    void cpmg_sizesThe90AndThe180FromRabiRate() {
        var seq = starter("cpmg").build(config(), circuitWithRf());
        var sorted = seq.clips().stream()
            .sorted((a, b) -> Double.compare(a.startTime(), b.startTime()))
            .toList();

        double expectedT90Micros = (Math.PI / 2.0) / (GAMMA * B1_MAX) * 1e6;
        double expectedT180Micros = 2 * expectedT90Micros;

        assertEquals(expectedT90Micros, sorted.get(0).duration(), 1e-6,
            "90 excitation duration should be pi/(2*gamma*B1)");
        for (int i = 1; i < sorted.size(); i++) {
            assertEquals(expectedT180Micros, sorted.get(i).duration(), 1e-6,
                "refocus pulse " + i + " should be twice the 90 duration");
        }
    }

    @Test
    void cpmg_allClipsAreConstantAtMaxAmplitude() {
        var seq = starter("cpmg").build(config(), circuitWithRf());
        for (var clip : seq.clips()) {
            assertInstanceOf(ClipShape.Constant.class, clip.shape(),
                "CPMG seeds constant (square) pulses so the flip angle is unambiguous");
            assertEquals(B1_MAX, clip.amplitude(), 1e-15);
        }
    }

    @Test
    void cpmg_clipsAreNonOverlapping_andInBounds() {
        var seq = starter("cpmg").build(config(), circuitWithRf());
        var sorted = seq.clips().stream()
            .sorted((a, b) -> Double.compare(a.startTime(), b.startTime()))
            .toList();
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i - 1).endTime() <= sorted.get(i).startTime() + 1e-9,
                "clip " + (i - 1) + " must end before clip " + i + " starts");
        }
        double lastEnd = sorted.getLast().endTime();
        assertTrue(lastEnd <= seq.totalDuration(),
            "last clip (ends at " + lastEnd + ") must fit inside totalDuration " + seq.totalDuration());
    }

    @Test
    void cpmg_bakeRoundTripIsClean() {
        var circuit = circuitWithRf();
        var seq = starter("cpmg").build(config(), circuit);
        var baked = ClipBaker.bake(seq, circuit);
        assertNotNull(baked);
        assertFalse(baked.segments().isEmpty());
        assertFalse(baked.pulseSegments().isEmpty());
    }

    @Test
    void cpmg_degradesToEmptyClipListWhenNoRfSource() {
        var seq = starter("cpmg").build(config(), circuitWithoutRf());
        assertTrue(seq.clips().isEmpty());
        assertEquals(1, seq.tracks().size(), "Gradient X track should still exist");
    }

    @Test
    void cpmg_handlesNullCircuit() {
        var seq = starter("cpmg").build(config(), null);
        assertNotNull(seq);
        assertTrue(seq.clips().isEmpty());
    }

    @Test
    void fallbackT90_whenGammaOrB1IsZero() {
        double fallback = SequenceStarterLibrary.computeT90Micros(0, 100e-6);
        assertTrue(fallback > 0, "zero gamma must still return a finite positive duration");
        double fallback2 = SequenceStarterLibrary.computeT90Micros(GAMMA, 0);
        assertTrue(fallback2 > 0, "zero B1 must still return a finite positive duration");
    }

    @Test
    void blankStarter_hasNoConfigStep() {
        assertNull(starter("blank").configStep(),
            "Blank starter has no parameters to configure");
    }

    @Test
    void carrPurcellStarters_exposeAConfigStep() {
        FxTestSupport.runOnFxThread(() -> {
            assertNotNull(starter("cpmg").configStep(),
                "CPMG must surface a customisation step so users can pick echo count and spacing");
            assertNotNull(starter("cp").configStep(),
                "CP must surface a customisation step so users can pick echo count and spacing");
        });
    }

    @Test
    void descriptionsAreAscii() {
        for (var s : SequenceStarterLibrary.all()) {
            for (int i = 0; i < s.description().length(); i++) {
                char c = s.description().charAt(i);
                assertTrue(c < 0x80,
                    "Description for '" + s.id() + "' contains non-ASCII char '" + c + "' (U+"
                        + Integer.toHexString(c) + "): " + s.description());
            }
            for (int i = 0; i < s.name().length(); i++) {
                char c = s.name().charAt(i);
                assertTrue(c < 0x80,
                    "Name for '" + s.id() + "' contains non-ASCII char '" + c + "': " + s.name());
            }
        }
    }
}
