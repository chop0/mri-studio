package ax.xz.mri.model.simulation;

/**
 * The numerical methods a {@link SimulationConfig} uses, keyed by substance
 * kind. A small extensible container so {@link SimulationConfig} doesn't grow
 * substance-specific fields inline — v1 carries only the NV technique; a Bloch
 * integrator choice or similar would join here later.
 */
public record SimulationMethods(NvSimulationMethod nv) {

    public SimulationMethods {
        if (nv == null) throw new IllegalArgumentException("SimulationMethods.nv must not be null");
    }

    /** All-default methods: NV centres simulated independently (classical). */
    public static SimulationMethods defaults() {
        return new SimulationMethods(NvSimulationMethod.independent());
    }

    public SimulationMethods withNv(NvSimulationMethod method) {
        return new SimulationMethods(method);
    }
}
