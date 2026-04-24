package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.AmplitudeKind;

import java.util.List;
import java.util.UUID;

/**
 * Single point of dispatch for component-kind-specific UI behaviour.
 *
 * <p>Add a new component? Do three things and only three things:
 * <ol>
 *   <li>Declare the record in {@link CircuitComponent} with its own
 *       {@code stamp}.</li>
 *   <li>Drop a {@code <Kind>Presenter} alongside this file.</li>
 *   <li>Add one {@code case} arm to {@link #of(CircuitComponent)} and one
 *       entry to {@link #paletteEntries()}.</li>
 * </ol>
 *
 * <p>None of the renderer / geometry / inspector / auto-layout / palette /
 * context-menu code needs to change.
 */
public final class ComponentPresenters {
    private ComponentPresenters() {}

    /** Returns the presenter for a live component instance. */
    public static ComponentPresenter of(CircuitComponent c) {
        return switch (c) {
            case CircuitComponent.VoltageSource v -> new VoltageSourcePresenter(v);
            case CircuitComponent.SwitchComponent s -> new SwitchPresenter(s);
            case CircuitComponent.Multiplexer m -> new MultiplexerPresenter(m);
            case CircuitComponent.Coil coil -> new CoilPresenter(coil);
            case CircuitComponent.Probe p -> new ProbePresenter(p);
            case CircuitComponent.Resistor r -> new ResistorPresenter(r);
            case CircuitComponent.Capacitor cap -> new CapacitorPresenter(cap);
            case CircuitComponent.Inductor l -> new InductorPresenter(l);
            case CircuitComponent.ShuntResistor sr -> new ShuntResistorPresenter(sr);
            case CircuitComponent.ShuntCapacitor sc -> new ShuntCapacitorPresenter(sc);
            case CircuitComponent.ShuntInductor sl -> new ShuntInductorPresenter(sl);
            case CircuitComponent.IdealTransformer t -> new IdealTransformerPresenter(t);
            case CircuitComponent.Mixer dc -> new MixerPresenter(dc);
        };
    }

    /**
     * Returns every palette-eligible component kind in display order,
     * grouped by section. The palette UI and the canvas context menu both
     * iterate this list — no hardcoded per-kind buttons.
     */
    public static List<ComponentPaletteEntry> paletteEntries() {
        return List.of(
            // Sources. Quadrature drives are now assembled from two REAL
            // sources (I and Q) feeding a Mixer block, so there is no
            // "RF source (I/Q)" palette entry — this is by design.
            new ComponentPaletteEntry("Sources", "Voltage source (real)", "Real-valued drive",
                () -> new CircuitComponent.VoltageSource(newId("src"), "Source " + shortId(),
                    AmplitudeKind.REAL, 0, 0, 1, 0)),
            new ComponentPaletteEntry("Sources", "Static source", "Fixed amplitude (B0-like)",
                () -> new CircuitComponent.VoltageSource(newId("src"), "Static " + shortId(),
                    AmplitudeKind.STATIC, 0, 0, 1, 0)),
            new ComponentPaletteEntry("Sources", "Gate source", "0/1 digital signal",
                () -> new CircuitComponent.VoltageSource(newId("src"), "Gate " + shortId(),
                    AmplitudeKind.GATE, 0, 0, 1, 0)),

            // Routing
            new ComponentPaletteEntry("Routing", "Switch", "Gated pass-through",
                () -> new CircuitComponent.SwitchComponent(newId("sw"), "Switch",
                    1e-6, 1e9, 0.5)),
            new ComponentPaletteEntry("Routing", "Multiplexer",
                "SPDT: a-to-common when ctl high, b-to-common when low",
                () -> new CircuitComponent.Multiplexer(newId("mux"), "Mux",
                    1e-6, 1e9, 0.5)),

            // Coils + probes
            new ComponentPaletteEntry("Coils + probes", "Coil", "Bridges circuit and FOV",
                () -> new CircuitComponent.Coil(newId("coil"), "Coil", null, 0, 0)),
            new ComponentPaletteEntry("Coils + probes", "Probe", "Voltage measurement",
                () -> new CircuitComponent.Probe(newId("probe"), "Probe",
                    1, 0, Double.POSITIVE_INFINITY)),
            new ComponentPaletteEntry("Coils + probes", "Mixer",
                "I/Q mixer: rotates its tap by exp(-j\u00b72\u03C0\u00b7loHz\u00b7t)",
                () -> new CircuitComponent.Mixer(newId("dc"), "DC", 0)),

            // Series passives
            new ComponentPaletteEntry("Series passives", "Resistor (series)",
                "Linear resistance inline",
                () -> new CircuitComponent.Resistor(newId("r"), "R", 50)),
            new ComponentPaletteEntry("Series passives", "Capacitor (series)",
                "Reactive capacitance inline",
                () -> new CircuitComponent.Capacitor(newId("c"), "C", 1e-9)),
            new ComponentPaletteEntry("Series passives", "Inductor (series)",
                "Reactive inductance inline",
                () -> new CircuitComponent.Inductor(newId("l"), "L", 1e-6)),

            // Parallel (shunt to ground)
            new ComponentPaletteEntry("Parallel (shunt to ground)", "Resistor (parallel)",
                "Shunt resistance to ground",
                () -> new CircuitComponent.ShuntResistor(newId("rshunt"), "Rp", 50)),
            new ComponentPaletteEntry("Parallel (shunt to ground)", "Capacitor (parallel)",
                "Shunt capacitance to ground",
                () -> new CircuitComponent.ShuntCapacitor(newId("cshunt"), "Cp", 1e-9)),
            new ComponentPaletteEntry("Parallel (shunt to ground)", "Inductor (parallel)",
                "Shunt inductance to ground",
                () -> new CircuitComponent.ShuntInductor(newId("lshunt"), "Lp", 1e-6))
        );
    }

    private static ComponentId newId(String prefix) {
        return new ComponentId(prefix + "-" + UUID.randomUUID());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 4);
    }
}
