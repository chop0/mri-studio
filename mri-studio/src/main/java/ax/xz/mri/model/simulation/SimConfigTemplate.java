package ax.xz.mri.model.simulation;

import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.model.simulation.dsl.ReceiveCoilStarterLibrary;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.List;

/**
 * Named starting-point templates for new simulation configs.
 *
 * <p>Each template creates a set of {@link TransmitCoil transmit coils},
 * {@link DrivePath drive paths}, and {@link ReceiveCoil receive coils}
 * backed by starter eigenfields in the starter library. Values are set for
 * a typical low-field MRI setup when applicable.
 */
public enum SimConfigTemplate {
    EMPTY("Empty", "No coils or paths \u2014 build from scratch") {
        @Override
        public List<TransmitCoil> createTransmitCoils(ProjectRepository repo) { return List.of(); }

        @Override
        public List<DrivePath> createDrivePaths(ProjectRepository repo) { return List.of(); }

        @Override
        public List<ReceiveCoil> createReceiveCoils(ProjectRepository repo) { return List.of(); }

        @Override
        public double referenceB0Tesla() { return 1.5; }

        @Override
        public WizardStep configStep() { return null; }
    },
    LOW_FIELD_MRI("Standard low-field \u00b9H MRI",
            "B0 + Gx + Gz + RF TX + RX coil + T/R switch gate for a ~15 mT Helmholtz system") {
        private LowFieldMriConfigStep step;

        @Override
        public List<TransmitCoil> createTransmitCoils(ProjectRepository repo) {
            var b0 = ensureEigenfield(repo, "B0 Helmholtz", "helmholtz-b0");
            var gx = ensureEigenfield(repo, "Gradient X", "gradient-x");
            var gz = ensureEigenfield(repo, "Gradient Z", "gradient-z");
            var rf = ensureEigenfield(repo, "RF Transverse", "uniform-b-perp");

            return List.of(
                new TransmitCoil("B0 Coil", b0.id(), 0.0),
                new TransmitCoil("Gx Coil", gx.id(), 0.0),
                new TransmitCoil("Gz Coil", gz.id(), 0.0),
                new TransmitCoil("RF Coil", rf.id(), 0.0)
            );
        }

        @Override
        public List<DrivePath> createDrivePaths(ProjectRepository repo) {
            double b0Tesla = step != null ? step.getB0Tesla() : 0.0154;
            double gamma = step != null ? step.getGamma() : 267.522e6;
            double larmorHz = gamma * b0Tesla / (2 * Math.PI);

            return List.of(
                new DrivePath("B0", "B0 Coil", AmplitudeKind.STATIC, 0, 0, b0Tesla, null),
                new DrivePath("RF", "RF Coil", AmplitudeKind.QUADRATURE, larmorHz, 0, 200e-6, null),
                new DrivePath("Gradient X", "Gx Coil", AmplitudeKind.REAL, 0, -0.030, 0.030, null),
                new DrivePath("Gradient Z", "Gz Coil", AmplitudeKind.REAL, 0, -0.030, 0.030, null),
                new DrivePath("RX Gate", null, AmplitudeKind.GATE, 0, 0, 1.0, null)
            );
        }

        @Override
        public List<ReceiveCoil> createReceiveCoils(ProjectRepository repo) {
            var starter = ReceiveCoilStarterLibrary.byId("uniform-isotropic").orElseThrow();
            var rxEigen = ensureEigenfield(repo, "RX Whole-volume", starter.eigenfieldStarterId());
            return List.of(new ReceiveCoil("Primary RX", rxEigen.id(),
                starter.gain(), starter.phaseDeg(), 0.0, "RX Gate"));
        }

        @Override
        public double referenceB0Tesla() {
            return step != null ? step.getB0Tesla() : 0.0154;
        }

        @Override
        public WizardStep configStep() {
            if (step == null) step = new LowFieldMriConfigStep();
            return step;
        }
    };

    private final String displayName;
    private final String description;

    SimConfigTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }

    public abstract List<TransmitCoil> createTransmitCoils(ProjectRepository repo);
    public abstract List<DrivePath> createDrivePaths(ProjectRepository repo);
    public abstract List<ReceiveCoil> createReceiveCoils(ProjectRepository repo);

    public abstract double referenceB0Tesla();
    public abstract WizardStep configStep();

    @Override
    public String toString() { return displayName; }

    private static ax.xz.mri.project.EigenfieldDocument ensureEigenfield(ProjectRepository repo, String name, String starterId) {
        var s = EigenfieldStarterLibrary.byId(starterId).orElseThrow();
        return ObjectFactory.findOrCreateEigenfield(repo, name, s.description(), s.source(), s.units(), s.defaultMagnitude());
    }
}
