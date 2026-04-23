package ax.xz.mri.model.field;

import ax.xz.mri.model.sequence.Segment;

import java.util.List;

/**
 * Runtime spatial field map consumed by the Bloch simulator.
 *
 * <p>Built by {@code BlochDataFactory} from a
 * {@link ax.xz.mri.model.simulation.SimulationConfig}. The simulator runs in a
 * rotating frame at angular frequency {@code ω_s = γ · b0Ref}; each driven
 * field is classified at build time:
 * <ul>
 *   <li><b>Static</b> fields ({@code AmplitudeKind.STATIC}) and any
 *       Bloch–Siegert / average-Hamiltonian corrections from fast fields are
 *       summed into {@link #staticBz}: the local Bz in the rotating frame.</li>
 *   <li><b>Dynamic</b> fields ({@code REAL} or {@code QUADRATURE}) have their
 *       eigenfield shape sampled on the (r, z) grid and stored in
 *       {@link #dynamicFields}.</li>
 * </ul>
 *
 * <p>Receive coils are stored separately in {@link #receiveCoils} — they
 * observe the magnetisation without contributing to the B-field integration.
 */
public final class FieldMap {
    /** Radial grid positions (mm), length {@code nR}. */
    public double[] rMm;

    /** Axial grid positions (mm), length {@code nZ}. */
    public double[] zMm;

    /** Rotating-frame reference: {@code ω_s = γ · b0Ref}, Tesla. */
    public double b0Ref;

    /**
     * Static Bz at each grid point, Tesla, already referenced: this is the
     * off-resonance, not the lab-frame field. Includes Bloch–Siegert
     * corrections from fast-oscillating fields. Indexed {@code [r][z]}.
     */
    public double[][] staticBz;

    /** Per-driven-field runtime maps, in pulse-channel order. */
    public List<DynamicFieldMap> dynamicFields;

    /** Named gate signals (no B-field contribution) that other paths and coils can read. */
    public List<GateMap> gates;

    /** Per-receive-coil sensitivity maps. */
    public List<ReceiveCoilMap> receiveCoils;

    /** Initial magnetisation components at each grid point. Indexed {@code [r][z]}. */
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
}
