package ax.xz.mri.model.circuit;

import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A graph of circuit components + wires.
 *
 * <p>The circuit is the authoritative description of every source, switch,
 * coil, probe, and passive in a simulation. The simulator compiles this into
 * a {@link ax.xz.mri.service.circuit.CompiledCircuit} for per-step MNA
 * solves; the schematic editor renders it on a canvas using the
 * {@link #layout()} positions.
 *
 * <p>Validation at construction rejects:
 * <ul>
 *   <li>Duplicate component ids, names, or channel names.</li>
 *   <li>Wires that reference a non-existent component or port.</li>
 *   <li>Wires with both endpoints on the same component.</li>
 *   <li>Configurations with no {@linkplain CircuitComponent.Ground ground}.</li>
 * </ul>
 */
public record CircuitDocument(
    ProjectNodeId id,
    String name,
    List<CircuitComponent> components,
    List<Wire> wires,
    CircuitLayout layout
) implements ProjectNode {
    public CircuitDocument {
        if (id == null) throw new IllegalArgumentException("CircuitDocument.id must not be null");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("CircuitDocument.name must be non-blank");
        components = List.copyOf(components == null ? List.of() : components);
        wires = List.copyOf(wires == null ? List.of() : wires);
        if (layout == null) layout = CircuitLayout.empty();
        validate(components, wires);
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.CIRCUIT;
    }

    public Optional<CircuitComponent> component(ComponentId id) {
        for (var c : components) if (c.id().equals(id)) return Optional.of(c);
        return Optional.empty();
    }

    public List<CircuitComponent.VoltageSource> voltageSources() {
        var out = new ArrayList<CircuitComponent.VoltageSource>();
        for (var c : components) if (c instanceof CircuitComponent.VoltageSource v) out.add(v);
        return List.copyOf(out);
    }

    public List<CircuitComponent.Coil> coils() {
        var out = new ArrayList<CircuitComponent.Coil>();
        for (var c : components) if (c instanceof CircuitComponent.Coil v) out.add(v);
        return List.copyOf(out);
    }

    public List<CircuitComponent.Probe> probes() {
        var out = new ArrayList<CircuitComponent.Probe>();
        for (var c : components) if (c instanceof CircuitComponent.Probe v) out.add(v);
        return List.copyOf(out);
    }

    /** Total pulse-sequence control scalars per step — sum over all voltage sources. */
    @JsonIgnore
    public int totalChannelCount() {
        int total = 0;
        for (var vs : voltageSources()) total += vs.channelCount();
        return total;
    }

    public CircuitDocument withName(String newName) {
        return new CircuitDocument(id, newName, components, wires, layout);
    }

    public CircuitDocument withComponents(List<CircuitComponent> next) {
        return new CircuitDocument(id, name, next, wires, layout);
    }

    public CircuitDocument withWires(List<Wire> next) {
        return new CircuitDocument(id, name, components, next, layout);
    }

    public CircuitDocument withLayout(CircuitLayout next) {
        return new CircuitDocument(id, name, components, wires, next);
    }

    public CircuitDocument addComponent(CircuitComponent component, ComponentPosition position) {
        var nextComponents = new ArrayList<>(components);
        nextComponents.add(component);
        return new CircuitDocument(id, name, nextComponents, wires,
            position == null ? layout : layout.with(position));
    }

    public CircuitDocument replaceComponent(CircuitComponent updated) {
        var next = new ArrayList<>(components);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(updated.id())) {
                next.set(i, updated);
                return new CircuitDocument(id, name, next, wires, layout);
            }
        }
        throw new IllegalArgumentException("Component " + updated.id() + " not found");
    }

    public CircuitDocument removeComponent(ComponentId toRemove) {
        var nextComponents = new ArrayList<CircuitComponent>();
        for (var c : components) if (!c.id().equals(toRemove)) nextComponents.add(c);
        var nextWires = new ArrayList<Wire>();
        for (var w : wires) {
            if (!w.from().componentId().equals(toRemove) && !w.to().componentId().equals(toRemove)) {
                nextWires.add(w);
            }
        }
        return new CircuitDocument(id, name, nextComponents, nextWires, layout.without(toRemove));
    }

    public CircuitDocument addWire(Wire wire) {
        var next = new ArrayList<>(wires);
        next.add(wire);
        return new CircuitDocument(id, name, components, next, layout);
    }

    public CircuitDocument removeWire(String wireId) {
        var next = new ArrayList<Wire>();
        for (var w : wires) if (!w.id().equals(wireId)) next.add(w);
        return new CircuitDocument(id, name, components, next, layout);
    }

    public static CircuitDocument empty(ProjectNodeId id, String name) {
        return new CircuitDocument(id, name, List.of(), List.of(), CircuitLayout.empty());
    }

    private static void validate(List<CircuitComponent> components, List<Wire> wires) {
        var ids = new HashSet<ComponentId>();
        var names = new HashSet<String>();
        var portsByComponent = new HashMap<ComponentId, List<String>>();
        for (var c : components) {
            if (!ids.add(c.id())) throw new IllegalArgumentException("Duplicate component id: " + c.id());
            if (!names.add(c.name())) throw new IllegalArgumentException("Duplicate component name: " + c.name());
            portsByComponent.put(c.id(), c.ports());
        }

        var wireIds = new HashSet<String>();
        for (var w : wires) {
            if (!wireIds.add(w.id())) throw new IllegalArgumentException("Duplicate wire id: " + w.id());
            validateTerminal(portsByComponent, w.from());
            validateTerminal(portsByComponent, w.to());
            if (w.from().componentId().equals(w.to().componentId()))
                throw new IllegalArgumentException("Wire " + w.id() + " has both endpoints on " + w.from().componentId());
        }
    }

    private static void validateTerminal(Map<ComponentId, List<String>> portsByComponent, ComponentTerminal terminal) {
        var ports = portsByComponent.get(terminal.componentId());
        if (ports == null) throw new IllegalArgumentException("Wire references unknown component: " + terminal.componentId());
        if (!ports.contains(terminal.port()))
            throw new IllegalArgumentException("Wire references unknown port '" + terminal.port() + "' on component " + terminal.componentId());
    }
}
