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
 * <p>Each template creates a set of {@link FieldDefinition driven fields} and
 * {@link ReceiveCoil receive coils} backed by starter eigenfields in the
 * starter library. Field amplitudes and carrier frequencies are set for a
 * typical low-field MRI setup when applicable.
 */
public enum SimConfigTemplate {
    EMPTY("Empty", "No fields or receive coils — build from scratch") {
        @Override
        public List<FieldDefinition> createFields(ProjectRepository repo) {
            return List.of();
        }

        @Override
        public List<ReceiveCoil> createReceiveCoils(ProjectRepository repo) {
            return List.of();
        }

        @Override
        public double referenceB0Tesla() { return 1.5; }

        @Override
        public WizardStep configStep() { return null; }
    },
    LOW_FIELD_MRI("Standard low-field \u00b9H MRI", "B0 + Gx + Gz + RF TX + RX coil for a ~15 mT Helmholtz system") {
        private LowFieldMriConfigStep step;

        @Override
        public List<FieldDefinition> createFields(ProjectRepository repo) {
            double b0Tesla = step != null ? step.getB0Tesla() : 0.0154;
            double gamma = step != null ? step.getGamma() : 267.522e6;

            var b0Starter = EigenfieldStarterLibrary.byId("helmholtz-b0").orElseThrow();
            var gxStarter = EigenfieldStarterLibrary.byId("gradient-x").orElseThrow();
            var gzStarter = EigenfieldStarterLibrary.byId("gradient-z").orElseThrow();
            var rfStarter = EigenfieldStarterLibrary.byId("uniform-b-perp").orElseThrow();

            var b0 = ObjectFactory.findOrCreateEigenfield(repo, "B0 Helmholtz", b0Starter.description(), b0Starter.source(), b0Starter.units(), b0Starter.defaultMagnitude());
            var gx = ObjectFactory.findOrCreateEigenfield(repo, "Gradient X", gxStarter.description(), gxStarter.source(), gxStarter.units(), gxStarter.defaultMagnitude());
            var gz = ObjectFactory.findOrCreateEigenfield(repo, "Gradient Z", gzStarter.description(), gzStarter.source(), gzStarter.units(), gzStarter.defaultMagnitude());
            var rf = ObjectFactory.findOrCreateEigenfield(repo, "RF Transverse", rfStarter.description(), rfStarter.source(), rfStarter.units(), rfStarter.defaultMagnitude());

            double larmorHz = gamma * b0Tesla / (2 * Math.PI);

            return List.of(
                new FieldDefinition("B0", b0.id(), AmplitudeKind.STATIC, 0, 0, b0Tesla),
                new FieldDefinition("RF", rf.id(), AmplitudeKind.QUADRATURE, larmorHz, 0, 200e-6),
                new FieldDefinition("Gradient X", gx.id(), AmplitudeKind.REAL, 0, -0.030, 0.030),
                new FieldDefinition("Gradient Z", gz.id(), AmplitudeKind.REAL, 0, -0.030, 0.030)
            );
        }

        @Override
        public List<ReceiveCoil> createReceiveCoils(ProjectRepository repo) {
            var starter = ReceiveCoilStarterLibrary.byId("uniform-isotropic").orElseThrow();
            var eigenStarter = EigenfieldStarterLibrary.byId(starter.eigenfieldStarterId()).orElseThrow();
            var rxEigen = ObjectFactory.findOrCreateEigenfield(
                repo, "RX Whole-volume", eigenStarter.description(),
                eigenStarter.source(), eigenStarter.units(), eigenStarter.defaultMagnitude());
            return List.of(new ReceiveCoil("Primary RX", rxEigen.id(), starter.gain(), starter.phaseDeg()));
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

    public abstract List<FieldDefinition> createFields(ProjectRepository repo);

    public abstract List<ReceiveCoil> createReceiveCoils(ProjectRepository repo);

    /** Reference B0 for the rotating frame that this template implies. */
    public abstract double referenceB0Tesla();

    public abstract WizardStep configStep();

    @Override
    public String toString() { return displayName; }
}
