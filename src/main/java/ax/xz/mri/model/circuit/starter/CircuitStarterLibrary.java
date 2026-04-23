package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitComponent.Coil;
import ax.xz.mri.model.circuit.CircuitComponent.Multiplexer;
import ax.xz.mri.model.circuit.CircuitComponent.Probe;
import ax.xz.mri.model.circuit.CircuitComponent.VoltageSource;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Built-in starter circuits shown in the new-circuit wizard. */
public final class CircuitStarterLibrary {
    private CircuitStarterLibrary() {}

    private static final List<CircuitStarter> STARTERS = List.of(
        new EmptyStarter(),
        new LowFieldMriStarter()
    );

    public static List<CircuitStarter> all() { return STARTERS; }

    public static Optional<CircuitStarter> byId(String id) {
        if (id == null) return Optional.empty();
        return STARTERS.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static CircuitStarter defaultStarter() { return STARTERS.get(1); }

    // Empty

    private static final class EmptyStarter implements CircuitStarter {
        @Override public String id() { return "empty"; }
        @Override public String name() { return "Empty"; }
        @Override public String description() { return "Blank schematic - build from scratch."; }
        @Override public CircuitDocument build(ProjectNodeId id, String name, ProjectRepository repository) {
            return CircuitDocument.empty(id, name);
        }
    }

    // Low-field MRI

    /**
     * Standard low-field 1H MRI setup: B0 static source + coil, RF quadrature
     * source + coil, two gradient sources + coils, and one primary probe wired
     * through a T/R multiplexer. The mux routes the RF source to the RF coil
     * during TX (RF.active high) and the coil to the probe during RX, so the
     * transmitter never drives the probe's front end directly.
     */
    private static final class LowFieldMriStarter implements CircuitStarter {
        @Override public String id() { return "low-field-mri"; }
        @Override public String name() { return "Low-field 1H MRI"; }
        @Override public String description() {
            return "B0 + RF + Gx + Gz + RX probe through a T/R multiplexer gated by RF.active.";
        }

        @Override
        public CircuitDocument build(ProjectNodeId id, String name, ProjectRepository repository) {
            double b0Tesla = 0.0154;
            double gamma = 267.522e6;
            double larmorHz = gamma * b0Tesla / (2 * Math.PI);

            var b0Eigen = ensureEigenfield(repository, "B0 Helmholtz", "helmholtz-b0");
            var gxEigen = ensureEigenfield(repository, "Gradient X", "gradient-x");
            var gzEigen = ensureEigenfield(repository, "Gradient Z", "gradient-z");
            var rfEigen = ensureEigenfield(repository, "RF Transverse", "uniform-b-perp");

            var b0Src = new VoltageSource(new ComponentId("src-b0"), "B0",
                AmplitudeKind.STATIC, 0, 0, b0Tesla, 0);
            var rfSrc = new VoltageSource(new ComponentId("src-rf"), "RF",
                AmplitudeKind.QUADRATURE, larmorHz, 0, 200e-6, 0);
            var gxSrc = new VoltageSource(new ComponentId("src-gx"), "Gradient X",
                AmplitudeKind.REAL, 0, -0.030, 0.030, 0);
            var gzSrc = new VoltageSource(new ComponentId("src-gz"), "Gradient Z",
                AmplitudeKind.REAL, 0, -0.030, 0.030, 0);

            var b0Coil = new Coil(new ComponentId("coil-b0"), "B0 Coil", b0Eigen.id(), 0, 0);
            var rfCoil = new Coil(new ComponentId("coil-rf"), "RF Coil", rfEigen.id(), 0, 0);
            var gxCoil = new Coil(new ComponentId("coil-gx"), "Gx Coil", gxEigen.id(), 0, 0);
            var gzCoil = new Coil(new ComponentId("coil-gz"), "Gz Coil", gzEigen.id(), 0, 0);

            // T/R mux: common -> RF coil, a -> RF source, b -> probe, ctl -> RF.active.
            var trMux = new Multiplexer(new ComponentId("mux-tr"), "T/R Mux",
                0.5, 1e9, 0.5);
            // Probe demodulates at the Larmor carrier so the reported signal
            // is baseband relative to the RF drive.
            var probe = new Probe(new ComponentId("probe-rx"), "Primary RX",
                1.0, larmorHz, 0.0, Double.POSITIVE_INFINITY);

            var components = List.<CircuitComponent>of(
                b0Src, rfSrc, gxSrc, gzSrc,
                b0Coil, rfCoil, gxCoil, gzCoil,
                trMux, probe);

            var wires = new ArrayList<Wire>();
            wires.add(wire("w-b0-drive", b0Src.id(), "out", b0Coil.id(), "in"));
            wires.add(wire("w-gx-drive", gxSrc.id(), "out", gxCoil.id(), "in"));
            wires.add(wire("w-gz-drive", gzSrc.id(), "out", gzCoil.id(), "in"));

            // T/R routing.
            wires.add(wire("w-rf-mux",    rfSrc.id(), "out", trMux.id(), "a"));
            wires.add(wire("w-probe-mux", probe.id(), "in",  trMux.id(), "b"));
            wires.add(wire("w-mux-coil",  trMux.id(), "common", rfCoil.id(), "in"));
            wires.add(wire("w-mux-ctl",   rfSrc.id(), "active", trMux.id(), "ctl"));

            var layout = LowFieldLayout.arrange(
                List.of(b0Src, rfSrc, gxSrc, gzSrc),
                List.of(b0Coil, rfCoil, gxCoil, gzCoil),
                trMux, probe);

            return new CircuitDocument(id, name, components, wires, layout);
        }
    }

    private static ax.xz.mri.project.EigenfieldDocument ensureEigenfield(
        ProjectRepository repo, String name, String starterId
    ) {
        var s = EigenfieldStarterLibrary.byId(starterId).orElseThrow();
        return ObjectFactory.findOrCreateEigenfield(repo, name, s.description(), s.source(), s.units(), s.defaultMagnitude());
    }

    private static Wire wire(String id, ComponentId a, String pa, ComponentId b, String pb) {
        return new Wire(id, new ComponentTerminal(a, pa), new ComponentTerminal(b, pb));
    }
}
