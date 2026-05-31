package ax.xz.mri.model.substance;

import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.output.SpinOutput;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

/**
 * A collection of spins anchored into the simulation FOV.
 *
 * <p>Every substance is "a collection of tiny coil-like things, each with a
 * position, a state, a kernel that says how it responds to local B-field,
 * and typed output channels". Continuous magnetisation has one spin per FOV
 * voxel; an NV ensemble has one spin per centre. The runtime is unified —
 * {@link ax.xz.mri.service.simulation.compiled.CompiledSimulation} pattern-
 * matches once at compile time to produce a fused per-step kernel.
 *
 * <p>Magnetic coupling between any moment-emitting spin and every coil in
 * the FOV is <em>implicit / ambient</em>: there is no wiring on the
 * schematic. The compiled simulation bakes per-spin per-coil reciprocity
 * weights at compile time and integrates them every step. Optical
 * coupling, by contrast, is <em>explicit</em>: substances that emit
 * {@link ax.xz.mri.model.substance.output.PhotonClickRate} expose one
 * output port per channel that wires to an
 * {@link ax.xz.mri.model.probe.OpticalCounter} probe.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContinuousMagnetisation.class, name = "continuous_magnetisation"),
    @JsonSubTypes.Type(value = NvEnsemble.class,              name = "nv_ensemble")
})
public sealed interface Substance permits ContinuousMagnetisation, NvEnsemble {

    /** What kind of spin this substance is made of. */
    SpinKind spinKind();

    /**
     * Symmetry hint the substance would like the simulation grid to use.
     * The compile step honours this only if every other component in the
     * sim also opts in; otherwise the grid falls back to
     * {@link FieldSymmetry#CARTESIAN_3D}.
     */
    FieldSymmetry preferredSymmetry();

    /** The {@link SpinOutput} types this substance emits. Used to type-check probe wiring. */
    Set<Class<? extends SpinOutput>> outputChannels();

    /**
     * Optional control inputs this substance reads from sequence tracks
     * (e.g. NV's {@code "laser_on"}). Each name binds to one sequence-track
     * scalar channel.
     */
    default Set<String> controlInputs() { return Set.of(); }

    /**
     * Half-extent of the smallest axis-aligned bounding box that contains
     * every spin this substance owns. Visualisations use this to derive
     * viewport bounds (the union over all substances). For
     * {@link ContinuousMagnetisation} this is the explicit spatial extent;
     * for {@link NvEnsemble} it is the bounding box of the centre list.
     */
    Vec3 halfExtent();
}
