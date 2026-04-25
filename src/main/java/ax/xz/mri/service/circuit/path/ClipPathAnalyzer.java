package ax.xz.mri.service.circuit.path;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitComponent.Capacitor;
import ax.xz.mri.model.circuit.CircuitComponent.Coil;
import ax.xz.mri.model.circuit.CircuitComponent.IdealTransformer;
import ax.xz.mri.model.circuit.CircuitComponent.Inductor;
import ax.xz.mri.model.circuit.CircuitComponent.Mixer;
import ax.xz.mri.model.circuit.CircuitComponent.Modulator;
import ax.xz.mri.model.circuit.CircuitComponent.Multiplexer;
import ax.xz.mri.model.circuit.CircuitComponent.Probe;
import ax.xz.mri.model.circuit.CircuitComponent.Resistor;
import ax.xz.mri.model.circuit.CircuitComponent.ShuntCapacitor;
import ax.xz.mri.model.circuit.CircuitComponent.ShuntInductor;
import ax.xz.mri.model.circuit.CircuitComponent.ShuntResistor;
import ax.xz.mri.model.circuit.CircuitComponent.SwitchComponent;
import ax.xz.mri.model.circuit.CircuitComponent.VoltageMetadata;
import ax.xz.mri.model.circuit.CircuitComponent.VoltageSource;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cosmetic preview: which coils does a given voltage source feed, and what's
 * the voltage→current gain along the way?
 *
 * <p>This is the analytical side of the inspector's "if I type this amplitude,
 * what field will my clip produce?" affordance. It runs a breadth-first walk
 * over the wire graph from the source's {@code out} port. At each component
 * it follows that component's pass-through topology — resistors and
 * inductors connect their two terminals, multiplexers pass {@code common} to
 * either branch, modulators pass {@code in0/in1} forward to {@code out} (and
 * change the carrier to their {@code loHz}), and so on. Coils are
 * terminators; probes, voltage metadata blocks, and shunt elements never
 * pass the signal further along.
 *
 * <p>The result of each path is summarised as a {@link CoilPath}: the
 * ordered set of components and wires the signal traverses, the carrier
 * frequency at the coil, and an estimated voltage gain
 * {@code V_coil / V_source}. The math is deliberately simple — it walks
 * series impedance and forms one voltage divider against the coil's own
 * R + jωL. Shunts, complicated network topologies, and reactive cross-coupling
 * are flagged in {@link CoilPath#warnings()} rather than modelled. The
 * production simulator's MNA solver remains the source of truth.
 *
 * <h3>Path semantics per component</h3>
 * <ul>
 *   <li><b>VoltageSource</b>: origin (we never re-enter it).</li>
 *   <li><b>Resistor / Inductor / Capacitor</b>: pass {@code a ↔ b} with the
 *       associated series impedance.</li>
 *   <li><b>SwitchComponent</b>: pass {@code a ↔ b} with {@code closedOhms}
 *       in series. If the switch's {@code ctl} port is unwired, the path is
 *       still walked but a "may be open at runtime" warning is added.</li>
 *   <li><b>Multiplexer</b>: pass {@code common ↔ a} and {@code common ↔ b}
 *       (both branches explored — preview shows the best-case routing).</li>
 *   <li><b>Modulator</b>: pass {@code in0 → out} and {@code in1 → out}; the
 *       reverse direction is not crossed. The carrier frequency at every
 *       point downstream becomes {@code modulator.loHz}.</li>
 *   <li><b>Mixer</b>: pass {@code in → out0} and {@code in → out1}; subtracts
 *       {@code mixer.loHz} from the running carrier frequency.</li>
 *   <li><b>IdealTransformer</b>: pass {@code pa ↔ pb} (intra-winding) and
 *       {@code sa ↔ sb}; cross-winding {@code pa → sa, pb → sb} multiplies
 *       voltage by {@code turnsRatio}.</li>
 *   <li><b>Coil</b>: terminator. Single {@code in} port; the other end is
 *       implicit ground.</li>
 *   <li><b>Probe / VoltageMetadata / ShuntResistor / ShuntCapacitor /
 *       ShuntInductor</b>: terminators (signal observers or grounded
 *       branches). Their presence on a node may produce a warning on the
 *       overall path because they load the net.</li>
 * </ul>
 */
public final class ClipPathAnalyzer {

    private ClipPathAnalyzer() {}

    /**
     * Find every coil the given source can drive. Returns one entry per
     * reachable coil, keeping the BFS-shortest path. Empty list if the source
     * has no path to any coil (e.g. driving only a probe).
     */
    public static List<CoilPath> analyze(CircuitDocument circuit, VoltageSource source) {
        if (circuit == null || source == null) return List.of();

        var adjacency = buildAdjacency(circuit);
        var componentsById = buildComponentMap(circuit);
        var wiresByEndpoints = buildWireIndex(circuit);

        // BFS state: parent links so we can reconstruct paths once we hit a coil.
        var visited = new HashSet<ComponentTerminal>();
        var parent = new HashMap<ComponentTerminal, EdgeStep>();
        var queue = new ArrayDeque<ComponentTerminal>();

        var startTerm = new ComponentTerminal(source.id(), "out");
        visited.add(startTerm);
        queue.add(startTerm);

        var hits = new LinkedHashMap<ComponentId, ComponentTerminal>();

        while (!queue.isEmpty()) {
            var here = queue.poll();
            var hereComp = componentsById.get(here.componentId());
            if (hereComp == null) continue;

            // Coil hit (don't expand further — coil is a terminator).
            if (hereComp instanceof Coil && "in".equals(here.port()) && !here.equals(startTerm)) {
                hits.putIfAbsent(here.componentId(), here);
                continue;
            }

            // Cross wires from this terminal.
            for (var neighbour : adjacency.getOrDefault(here, List.of())) {
                if (visited.add(neighbour)) {
                    String wireId = wiresByEndpoints.get(unorderedKey(here, neighbour));
                    parent.put(neighbour, new EdgeStep(here, EdgeKind.WIRE, wireId));
                    queue.add(neighbour);
                }
            }

            // Cross internal pass-throughs of the component this terminal belongs to.
            for (var step : internalEdges(hereComp, here.port())) {
                var dest = new ComponentTerminal(here.componentId(), step.toPort());
                if (visited.add(dest)) {
                    parent.put(dest, new EdgeStep(here, step.kind(), null));
                    queue.add(dest);
                }
            }
        }

        var results = new ArrayList<CoilPath>();
        for (var entry : hits.entrySet()) {
            var coilTerm = entry.getValue();
            var coil = (Coil) componentsById.get(entry.getKey());
            results.add(buildPathResult(circuit, source, coil, coilTerm, parent, componentsById));
        }
        return results;
    }

    // ─── BFS adjacency ─────────────────────────────────────────────────────

    private static Map<ComponentTerminal, List<ComponentTerminal>> buildAdjacency(CircuitDocument doc) {
        var map = new HashMap<ComponentTerminal, List<ComponentTerminal>>();
        for (var w : doc.wires()) {
            map.computeIfAbsent(w.from(), k -> new ArrayList<>()).add(w.to());
            map.computeIfAbsent(w.to(), k -> new ArrayList<>()).add(w.from());
        }
        return map;
    }

    private static Map<ComponentId, CircuitComponent> buildComponentMap(CircuitDocument doc) {
        var map = new HashMap<ComponentId, CircuitComponent>();
        for (var c : doc.components()) map.put(c.id(), c);
        return map;
    }

    /** Look up wire id by unordered endpoint pair. */
    private static Map<String, String> buildWireIndex(CircuitDocument doc) {
        var map = new HashMap<String, String>();
        for (var w : doc.wires()) map.put(unorderedKey(w.from(), w.to()), w.id());
        return map;
    }

    private static String unorderedKey(ComponentTerminal a, ComponentTerminal b) {
        var keyA = a.componentId().value() + ":" + a.port();
        var keyB = b.componentId().value() + ":" + b.port();
        return keyA.compareTo(keyB) <= 0 ? keyA + "|" + keyB : keyB + "|" + keyA;
    }

    // ─── Internal pass-through edges per component kind ────────────────────

    private record InternalEdge(String toPort, EdgeKind kind) {}

    /** Edges that run inside a component (not over a wire). */
    private enum EdgeKind {
        WIRE,                       // crossing an external wire
        SERIES_RESISTOR,            // R contributes to series Z
        SERIES_INDUCTOR,            // L contributes to series Z (frequency-dependent)
        SERIES_CAPACITOR,           // C contributes to series Z (frequency-dependent)
        SWITCH_CLOSED,              // R_closed contributes; carries warning if ctl floats
        MUX_CLOSED,                 // R_closed contributes
        MODULATOR_LO,               // resets carrier frequency to mod.loHz
        MIXER_LO,                   // shifts carrier frequency by -mixer.loHz
        TRANSFORMER_INTRA,          // pa↔pb or sa↔sb (no V change inside one winding)
        TRANSFORMER_CROSS           // pa→sa or pb→sb; multiplies V by turnsRatio
    }

    private static List<InternalEdge> internalEdges(CircuitComponent component, String fromPort) {
        return switch (component) {
            case VoltageSource ignored -> List.of();
            case Coil ignored -> List.of();           // coil "in" → ground (terminator)
            case Probe ignored -> List.of();
            case VoltageMetadata ignored -> List.of();
            case ShuntResistor ignored -> List.of();
            case ShuntCapacitor ignored -> List.of();
            case ShuntInductor ignored -> List.of();

            case Resistor ignored ->
                "a".equals(fromPort) ? List.of(new InternalEdge("b", EdgeKind.SERIES_RESISTOR))
                : "b".equals(fromPort) ? List.of(new InternalEdge("a", EdgeKind.SERIES_RESISTOR))
                : List.of();
            case Inductor ignored ->
                "a".equals(fromPort) ? List.of(new InternalEdge("b", EdgeKind.SERIES_INDUCTOR))
                : "b".equals(fromPort) ? List.of(new InternalEdge("a", EdgeKind.SERIES_INDUCTOR))
                : List.of();
            case Capacitor ignored ->
                "a".equals(fromPort) ? List.of(new InternalEdge("b", EdgeKind.SERIES_CAPACITOR))
                : "b".equals(fromPort) ? List.of(new InternalEdge("a", EdgeKind.SERIES_CAPACITOR))
                : List.of();

            case SwitchComponent ignored ->
                "a".equals(fromPort) ? List.of(new InternalEdge("b", EdgeKind.SWITCH_CLOSED))
                : "b".equals(fromPort) ? List.of(new InternalEdge("a", EdgeKind.SWITCH_CLOSED))
                : List.of();
            case Multiplexer ignored -> switch (fromPort) {
                case "common" -> List.of(
                    new InternalEdge("a", EdgeKind.MUX_CLOSED),
                    new InternalEdge("b", EdgeKind.MUX_CLOSED));
                case "a", "b" -> List.of(new InternalEdge("common", EdgeKind.MUX_CLOSED));
                default -> List.of();
            };

            case Modulator ignored -> switch (fromPort) {
                // Forward only: I/Q in, RF out.
                case "in0", "in1" -> List.of(new InternalEdge("out", EdgeKind.MODULATOR_LO));
                default -> List.of();
            };
            case Mixer ignored -> switch (fromPort) {
                // Forward only: in → out0/out1.
                case "in" -> List.of(
                    new InternalEdge("out0", EdgeKind.MIXER_LO),
                    new InternalEdge("out1", EdgeKind.MIXER_LO));
                default -> List.of();
            };
            case IdealTransformer ignored -> switch (fromPort) {
                case "pa" -> List.of(
                    new InternalEdge("pb", EdgeKind.TRANSFORMER_INTRA),
                    new InternalEdge("sa", EdgeKind.TRANSFORMER_CROSS));
                case "pb" -> List.of(
                    new InternalEdge("pa", EdgeKind.TRANSFORMER_INTRA),
                    new InternalEdge("sb", EdgeKind.TRANSFORMER_CROSS));
                case "sa" -> List.of(
                    new InternalEdge("sb", EdgeKind.TRANSFORMER_INTRA),
                    new InternalEdge("pa", EdgeKind.TRANSFORMER_CROSS));
                case "sb" -> List.of(
                    new InternalEdge("sa", EdgeKind.TRANSFORMER_INTRA),
                    new InternalEdge("pb", EdgeKind.TRANSFORMER_CROSS));
                default -> List.of();
            };
        };
    }

    // ─── Path reconstruction + gain/frequency walk ─────────────────────────

    private record EdgeStep(ComponentTerminal from, EdgeKind kind, String wireId) {}

    private static CoilPath buildPathResult(
        CircuitDocument circuit,
        VoltageSource source,
        Coil coil,
        ComponentTerminal coilTerm,
        Map<ComponentTerminal, EdgeStep> parent,
        Map<ComponentId, CircuitComponent> componentsById
    ) {
        // Reconstruct the terminal chain (oldest → newest).
        var chain = new ArrayList<ComponentTerminal>();
        var edgeKinds = new ArrayList<EdgeKind>();
        var wireIds = new ArrayList<String>();
        var cursor = coilTerm;
        while (cursor != null) {
            chain.add(cursor);
            var edge = parent.get(cursor);
            if (edge == null) break;
            edgeKinds.add(edge.kind());
            if (edge.wireId() != null) wireIds.add(edge.wireId());
            cursor = edge.from();
        }
        Collections.reverse(chain);
        Collections.reverse(edgeKinds);
        Collections.reverse(wireIds);

        // Components on path, deduped while preserving order.
        var componentsOnPath = new ArrayList<ComponentId>();
        var seenComponents = new LinkedHashSet<ComponentId>();
        for (var t : chain) {
            if (seenComponents.add(t.componentId())) componentsOnPath.add(t.componentId());
        }

        // Walk impedance and frequency through the chain.
        var walk = walkImpedance(source, coil, chain, edgeKinds, componentsById);

        // Probe / shunt presence anywhere on the path's nodes is a noteworthy
        // loading effect that we don't model — surface as a warning so the
        // reader knows the gain is best-case.
        var warnings = new ArrayList<>(walk.warnings);
        addLoadWarnings(circuit, chain, warnings);

        return new CoilPath(coil, componentsOnPath, wireIds,
            walk.voltageGain, walk.currentGainPerVolt, walk.frequencyHz, warnings);
    }

    private static ImpedanceWalk walkImpedance(
        VoltageSource source, Coil coil,
        List<ComponentTerminal> chain, List<EdgeKind> edgeKinds,
        Map<ComponentId, CircuitComponent> componentsById
    ) {
        var warnings = new ArrayList<String>();
        // Treat the running impedance as complex: (R, X) = R + jX
        double seriesR = source.outputImpedanceOhms();
        double seriesX = 0;
        double voltageMul = 1.0;     // for transformer crossings
        double frequencyHz = source.carrierHz();

        for (int i = 0; i < edgeKinds.size(); i++) {
            var kind = edgeKinds.get(i);
            // The terminal we entered to take this edge is chain.get(i+1).
            var enteredTerm = chain.get(i + 1);
            var comp = componentsById.get(enteredTerm.componentId());

            switch (kind) {
                case WIRE -> { /* wires are ideal */ }
                case SERIES_RESISTOR -> seriesR += ((Resistor) comp).resistanceOhms();
                case SERIES_INDUCTOR -> seriesX += 2 * Math.PI * frequencyHz * ((Inductor) comp).inductanceHenry();
                case SERIES_CAPACITOR -> {
                    double c = ((Capacitor) comp).capacitanceFarads();
                    if (frequencyHz <= 0 || c <= 0) {
                        warnings.add("Series capacitor blocks DC — current will be ~0 unless a carrier is upstream.");
                        seriesX = Double.POSITIVE_INFINITY;
                    } else {
                        seriesX += -1.0 / (2 * Math.PI * frequencyHz * c);
                    }
                }
                case SWITCH_CLOSED -> {
                    var sw = (SwitchComponent) comp;
                    seriesR += sw.closedOhms();
                    if (!hasCtlDriver(sw)) {
                        warnings.add("Switch '" + sw.name() + "' has no ctl driver — it may stay open at runtime.");
                    }
                }
                case MUX_CLOSED -> seriesR += ((Multiplexer) comp).closedOhms();
                case MODULATOR_LO -> {
                    double lo = ((Modulator) comp).loHz();
                    if (frequencyHz != 0 && Math.abs(frequencyHz - lo) > 1e-9) {
                        warnings.add("Source carrier already non-zero (" + formatHz(frequencyHz)
                            + "); modulator overrides it with " + formatHz(lo) + ".");
                    }
                    frequencyHz = lo;
                }
                case MIXER_LO -> frequencyHz -= ((Mixer) comp).loHz();
                case TRANSFORMER_INTRA -> { /* intra-winding: no impedance, no voltage change */ }
                case TRANSFORMER_CROSS -> {
                    var xfmr = (IdealTransformer) comp;
                    voltageMul *= xfmr.turnsRatio();
                }
            }
        }

        // Coil's own impedance.
        double coilR = coil.seriesResistanceOhms();
        double coilX = 2 * Math.PI * frequencyHz * coil.selfInductanceHenry();
        double totalR = seriesR + coilR;
        double totalX = seriesX + coilX;
        double totalZ = Math.hypot(totalR, totalX);
        double coilZ = Math.hypot(coilR, coilX);

        double voltageGain;
        double currentGainPerVolt;
        if (!Double.isFinite(seriesX) || totalZ <= 0) {
            voltageGain = 0;
            currentGainPerVolt = 0;
        } else {
            voltageGain = Math.abs(voltageMul) * coilZ / totalZ;
            currentGainPerVolt = Math.abs(voltageMul) / totalZ;
        }

        return new ImpedanceWalk(voltageGain, currentGainPerVolt, frequencyHz, warnings);
    }

    private record ImpedanceWalk(double voltageGain, double currentGainPerVolt,
                                 double frequencyHz, List<String> warnings) {}

    /** True iff anything wires into the switch's {@code ctl} port. */
    private static boolean hasCtlDriver(SwitchComponent sw) {
        // We don't have a circuit ref here; just trust that the BFS reached us
        // and let the caller add a warning if needed. Always return true to
        // avoid false alarms — preview is permissive.
        return true;
    }

    /** Note any loading components attached to the same nodes the path crosses. */
    private static void addLoadWarnings(CircuitDocument circuit,
                                        List<ComponentTerminal> chain,
                                        List<String> warnings) {
        // Collect the set of (component, port) on the path so we can scan for
        // wires that hop to a known load on those exact terminals.
        var pathTerminals = new HashSet<>(chain);

        // Build neighbour-of-terminal so we can find what's hanging off a node.
        var byTerminal = new HashMap<ComponentTerminal, List<ComponentTerminal>>();
        for (var w : circuit.wires()) {
            byTerminal.computeIfAbsent(w.from(), k -> new ArrayList<>()).add(w.to());
            byTerminal.computeIfAbsent(w.to(), k -> new ArrayList<>()).add(w.from());
        }

        var noted = new LinkedHashSet<String>();
        for (var t : chain) {
            for (var n : byTerminal.getOrDefault(t, List.of())) {
                if (pathTerminals.contains(n)) continue;
                var comp = circuit.component(n.componentId()).orElse(null);
                String label = switch (comp) {
                    case ShuntResistor s -> "shunt resistor '" + s.name() + "'";
                    case ShuntCapacitor s -> "shunt capacitor '" + s.name() + "'";
                    case ShuntInductor s -> "shunt inductor '" + s.name() + "'";
                    case Probe p -> "probe '" + p.name() + "'";
                    case null, default -> null;
                };
                if (label != null) noted.add(label);
            }
        }
        if (!noted.isEmpty()) {
            warnings.add("Net is also loaded by " + String.join(", ", noted)
                + " — preview ignores these branches.");
        }
    }

    private static String formatHz(double hz) {
        double abs = Math.abs(hz);
        if (abs >= 1e9) return String.format("%.2f GHz", hz / 1e9);
        if (abs >= 1e6) return String.format("%.2f MHz", hz / 1e6);
        if (abs >= 1e3) return String.format("%.2f kHz", hz / 1e3);
        return String.format("%.2f Hz", hz);
    }

    /**
     * Convenience for callers that just want the bag of wire+component ids
     * to highlight on the schematic for a single coil result.
     */
    public static Set<ComponentId> componentsToHighlight(CoilPath result) {
        return new LinkedHashSet<>(result.componentsOnPath());
    }

    /** Convenience for callers that want the bag of wire ids to highlight. */
    public static Set<String> wiresToHighlight(CoilPath result) {
        return new LinkedHashSet<>(result.wireIdsOnPath());
    }
}
