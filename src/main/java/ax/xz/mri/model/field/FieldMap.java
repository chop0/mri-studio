package ax.xz.mri.model.field;

import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.service.circuit.CompiledCircuit;

import java.util.List;

/**
 * Runtime spatial + circuit state consumed by the simulator.
 *
 * <p>The {@link CompiledCircuit} encapsulates every source, switch, coil,
 * and probe the simulation uses — their eigenfield samples, topology links,
 * and channel offsets. The tissue / grid / reference-frame fields around it
 * are the non-circuit physics knobs.
 *
 * <p>{@link #staticBz} holds the always-on longitudinal field (in Tesla,
 * rotating-frame referenced: reference B0 is subtracted). Populated at build
 * time by summing every STATIC voltage source's contribution through its
 * target coil's eigenfield.
 */
public final class FieldMap {
    /** Radial grid positions (mm), length {@code nR}. */
    public double[] rMm;

    /** Axial grid positions (mm), length {@code nZ}. */
    public double[] zMm;

    /** Rotating-frame reference {@code ω_s = γ · b0Ref}, Tesla. */
    public double b0Ref;

    /** Static Bz at each grid point (Tesla, rotating-frame-referenced). */
    public double[][] staticBz;

    /** Initial magnetisation at each grid point. */
    public double[][] mx0;
    public double[][] my0;
    public double[][] mz0;

    public double fovX;
    public double fovZ;
    public double gamma;
    public double t1;
    public double t2;
    public List<Segment> segments;

    /** Slice half-thickness (metres). Null defaults to 5 mm at consumer sites. */
    public Double sliceHalf;

    /** Pre-compiled circuit: sources, switches, coils, probes, topology, passives. */
    public CompiledCircuit circuit;
}
