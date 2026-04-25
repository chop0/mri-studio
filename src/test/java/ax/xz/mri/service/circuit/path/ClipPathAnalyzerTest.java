package ax.xz.mri.service.circuit.path;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClipPathAnalyzerTest {

    private static final ProjectNodeId EF = new ProjectNodeId("ef-x");

    // ───────────── Direct source → coil ─────────────

    @Test
    void directSourceToCoilFindsTheCoilAndReportsRoughGain() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var coil = coil("coil", "Coil", 0, 2.0, 1.0);
        var doc = doc(List.of(src, coil), List.of(wire("w", src, "out", coil, "in")));

        var paths = ClipPathAnalyzer.analyze(doc, src);
        assertEquals(1, paths.size(), "one direct path");
        var p = paths.get(0);
        assertEquals(coil.id(), p.coil().id());
        // Direct connection: V_coil = V_src × R_coil / (R_src + R_coil) = 1 × 2/(0+2) = 1.0
        assertEquals(1.0, p.voltageGain(), 1e-9);
        // I_coil / V_src = 1/(R_coil) = 0.5 A/V
        assertEquals(0.5, p.currentGainPerVolt(), 1e-9);
        assertEquals(0.0, p.frequencyHz(), 1e-9);
        assertEquals(List.of(src.id(), coil.id()), p.componentsOnPath());
        assertEquals(List.of("w"), p.wireIdsOnPath());
    }

    // ───────────── Series resistor in path ─────────────

    @Test
    void seriesResistorReducesVoltageGainAndCurrent() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var r = new CircuitComponent.Resistor(new ComponentId("r"), "R", 3.0);
        var coil = coil("coil", "Coil", 0, 1.0, 1.0);
        var doc = doc(List.of(src, r, coil), List.of(
            wire("w1", src, "out", r, "a"),
            wire("w2", r, "b", coil, "in")
        ));

        var paths = ClipPathAnalyzer.analyze(doc, src);
        assertEquals(1, paths.size());
        var p = paths.get(0);
        // V_coil/V_src = R_coil / (R_src + R_path + R_coil) = 1/(0+3+1) = 0.25
        assertEquals(0.25, p.voltageGain(), 1e-9);
        // I_coil/V_src = 1/(R_path + R_coil) = 1/4 = 0.25 A/V
        assertEquals(0.25, p.currentGainPerVolt(), 1e-9);
        assertTrue(p.componentsOnPath().contains(r.id()), "path includes R");
    }

    // ───────────── Modulator on the path ─────────────

    @Test
    void modulatorPropagatesCarrierFrequencyToCoil() {
        var rfI = source("src-i", "RF I", AmplitudeKind.REAL, 1.0);
        var mod = new CircuitComponent.Modulator(new ComponentId("mod"), "Mod", 655_000);
        var coil = coil("coil", "RF Coil", 0, 1.0, 1.0);
        var doc = doc(List.of(rfI, mod, coil), List.of(
            wire("w-i", rfI, "out", mod, "in0"),
            wire("w-out", mod, "out", coil, "in")
        ));

        var paths = ClipPathAnalyzer.analyze(doc, rfI);
        assertEquals(1, paths.size());
        var p = paths.get(0);
        assertEquals(655_000.0, p.frequencyHz(), 1e-6);
        assertTrue(p.componentsOnPath().contains(mod.id()), "modulator is on path");
        // Coil R=1, L=0 ⇒ |Z| = 1; gain = 1/(0+1) = 1.0.
        assertEquals(1.0, p.voltageGain(), 1e-9);
    }

    // ───────────── Mixer subtracts loHz ─────────────

    @Test
    void mixerInPathSubtractsItsLoFromTheCarrier() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        // Force a non-zero starting carrier so the mixer has something to subtract from.
        var srcCarrier = new CircuitComponent.VoltageSource(
            new ComponentId("src-c"), "Sc", AmplitudeKind.REAL, 1_000_000, 0, 1, 0);
        var mixer = new CircuitComponent.Mixer(new ComponentId("mx"), "Mix", 200_000);
        var coil = coil("coil", "Coil", 0, 1.0, 1.0);
        var doc = doc(List.of(srcCarrier, mixer, coil), List.of(
            wire("w-in", srcCarrier, "out", mixer, "in"),
            wire("w-out", mixer, "out0", coil, "in")
        ));

        var paths = ClipPathAnalyzer.analyze(doc, srcCarrier);
        assertEquals(1, paths.size(), "one path through mixer");
        var p = paths.get(0);
        assertEquals(800_000.0, p.frequencyHz(), 1e-6, "carrier - loHz");
        // Avoid unused-warning on src
        assertNotNull(src);
    }

    // ───────────── Multiple coils ─────────────

    @Test
    void modulatorFanOutToTwoCoilsReturnsBothPaths() {
        var rfI = source("src-i", "RF I", AmplitudeKind.REAL, 1.0);
        var mod = new CircuitComponent.Modulator(new ComponentId("mod"), "Mod", 1_000_000);
        var coil1 = coil("c1", "RF Coil", 0, 1.0, 1.0);
        var coil2 = coil("c2", "Pickup Coil", 0, 1.0, 0.5);
        var doc = doc(List.of(rfI, mod, coil1, coil2), List.of(
            wire("w-i", rfI, "out", mod, "in0"),
            wire("w-c1", mod, "out", coil1, "in"),
            wire("w-c2", mod, "out", coil2, "in")
        ));
        var paths = ClipPathAnalyzer.analyze(doc, rfI);
        assertEquals(2, paths.size());
        assertTrue(paths.stream().anyMatch(p -> p.coil().id().equals(coil1.id())));
        assertTrue(paths.stream().anyMatch(p -> p.coil().id().equals(coil2.id())));
    }

    // ───────────── Multiplexer fan-out ─────────────

    @Test
    void multiplexerExposesBothBranchesAsReachable() {
        // Source feeds mux.common; mux.a → coil1, mux.b → coil2. Both
        // branches walked since the preview can't know which the user means.
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var mux = new CircuitComponent.Multiplexer(
            new ComponentId("mux"), "Mux", 0.001, 1e9, 0.5);
        var coil1 = coil("c1", "Coil 1", 0, 1.0, 1.0);
        var coil2 = coil("c2", "Coil 2", 0, 1.0, 1.0);
        var doc = doc(List.of(src, mux, coil1, coil2), List.of(
            wire("w-c", src, "out", mux, "common"),
            wire("w-a", mux, "a", coil1, "in"),
            wire("w-b", mux, "b", coil2, "in")
        ));
        var paths = ClipPathAnalyzer.analyze(doc, src);
        assertEquals(2, paths.size(), "both branches reachable");
    }

    // ───────────── Probes don't pass the signal further ─────────────

    @Test
    void probeOnTheNetIsNotATerminator_butAddsAWarning() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var coil = coil("coil", "Coil", 0, 1.0, 1.0);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);
        var doc = doc(List.of(src, coil, probe), List.of(
            wire("w-drive", src, "out", coil, "in"),
            wire("w-tap", probe, "in", coil, "in")  // probe taps the coil's net
        ));
        var paths = ClipPathAnalyzer.analyze(doc, src);
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).warnings().stream()
            .anyMatch(w -> w.contains("probe")), "probe loading is flagged");
    }

    // ───────────── No coil reachable ─────────────

    @Test
    void sourceWithNoCoilDownstreamReturnsEmpty() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);
        var doc = doc(List.of(src, probe), List.of(wire("w", src, "out", probe, "in")));
        assertEquals(List.of(), ClipPathAnalyzer.analyze(doc, src));
    }

    // ───────────── Coil current at amplitude ─────────────

    @Test
    void coilCurrentScalesLinearlyWithSourceAmplitude() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var coil = coil("coil", "Coil", 0, 4.0, 1.0);
        var doc = doc(List.of(src, coil), List.of(wire("w", src, "out", coil, "in")));
        var p = ClipPathAnalyzer.analyze(doc, src).get(0);

        // 1V → 0.25 A; 5 V → 1.25 A
        assertEquals(0.25, p.currentAmpsAt(1.0), 1e-9);
        assertEquals(1.25, p.currentAmpsAt(5.0), 1e-9);
    }

    // ───────────── Frequency-dependent inductance ─────────────

    @Test
    void coilInductanceLowersGainAtHigherFrequency() {
        var src = source("src", "S", AmplitudeKind.REAL, 1.0);
        var mod = new CircuitComponent.Modulator(new ComponentId("mod"), "Mod", 1_000_000);
        // Coil with R = 1, L = 1 µH ⇒ at 1 MHz, X_L = 2π·10⁶·10⁻⁶ ≈ 6.28
        // |Z_coil| = √(1 + 6.28²) ≈ 6.36 ⇒ gain = 6.36/6.36 = 1.0 (no series Z)
        var coil = coil("coil", "Coil", 1e-6, 1.0, 1.0);
        var doc = doc(List.of(src, mod, coil), List.of(
            wire("w-i", src, "out", mod, "in0"),
            wire("w-out", mod, "out", coil, "in")
        ));
        var p = ClipPathAnalyzer.analyze(doc, src).get(0);
        // No series impedance besides the coil ⇒ V_coil = V_source ⇒ gain = 1.
        assertEquals(1.0, p.voltageGain(), 1e-9);
        // I/V = 1/|Z_coil|; with X_L ≈ 6.283 and R = 1 ⇒ |Z| ≈ 6.362
        double expectedZ = Math.hypot(1.0, 2 * Math.PI * 1e6 * 1e-6);
        assertEquals(1.0 / expectedZ, p.currentGainPerVolt(), 1e-9);
    }

    // ───────────── Helpers ─────────────

    private static CircuitComponent.VoltageSource source(String id, String name,
                                                         AmplitudeKind kind, double amp) {
        return new CircuitComponent.VoltageSource(new ComponentId(id), name, kind,
            0, 0, amp, 0);
    }

    private static CircuitComponent.Coil coil(String id, String name, double l, double r,
                                               double sensitivity) {
        return new CircuitComponent.Coil(new ComponentId(id), name, EF, l, r, sensitivity);
    }

    private static Wire wire(String id, CircuitComponent a, String pa,
                             CircuitComponent b, String pb) {
        return new Wire(id, new ComponentTerminal(a.id(), pa),
                            new ComponentTerminal(b.id(), pb));
    }

    private static CircuitDocument doc(List<CircuitComponent> components, List<Wire> wires) {
        return new CircuitDocument(new ProjectNodeId("c"), "c",
            components, wires, CircuitLayout.empty());
    }
}
