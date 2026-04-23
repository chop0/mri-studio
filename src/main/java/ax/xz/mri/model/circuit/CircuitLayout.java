package ax.xz.mri.model.circuit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable map of canvas positions for a circuit's components. */
public record CircuitLayout(Map<ComponentId, ComponentPosition> positions) {
    public CircuitLayout {
        positions = Collections.unmodifiableMap(new LinkedHashMap<>(positions == null ? Map.of() : positions));
    }

    public static CircuitLayout empty() {
        return new CircuitLayout(Map.of());
    }

    public Optional<ComponentPosition> positionOf(ComponentId id) {
        return Optional.ofNullable(positions.get(id));
    }

    public CircuitLayout with(ComponentPosition position) {
        var next = new LinkedHashMap<>(positions);
        next.put(position.id(), position);
        return new CircuitLayout(next);
    }

    public CircuitLayout without(ComponentId id) {
        if (!positions.containsKey(id)) return this;
        var next = new LinkedHashMap<>(positions);
        next.remove(id);
        return new CircuitLayout(next);
    }
}
