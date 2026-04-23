package ax.xz.mri.service;

import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

import java.util.UUID;

/** Centralised creation logic for project objects. */
public final class ObjectFactory {
    private ObjectFactory() {}

    public record PhysicsParams(
        double gamma,
        double t1Ms, double t2Ms,
        double sliceHalfMm, double fovZMm, double fovRMm,
        int nZ, int nR,
        double dtSeconds
    ) {
        public static final PhysicsParams DEFAULTS =
            new PhysicsParams(267.522e6, 1000, 100, 5, 20, 30, 50, 5, 1e-6);
    }

    public static SimulationConfig buildConfig(PhysicsParams p, double referenceB0Tesla, ProjectNodeId circuitId) {
        return new SimulationConfig(
            p.t1Ms, p.t2Ms, p.gamma,
            p.sliceHalfMm, p.fovZMm, p.fovRMm,
            Math.max(2, p.nZ), Math.max(2, p.nR),
            referenceB0Tesla,
            p.dtSeconds,
            circuitId
        );
    }

    /** Find or create an eigenfield matching name + script. */
    public static EigenfieldDocument findOrCreateEigenfield(
            ProjectRepository repo, String name, String description, String script, String units, double defaultMagnitude) {
        for (var id : repo.eigenfieldIds()) {
            var node = repo.node(id);
            if (node instanceof EigenfieldDocument ef && ef.name().equals(name) && ef.script().equals(script)) {
                return ef;
            }
        }
        var eigen = new EigenfieldDocument(
            new ProjectNodeId("ef-" + UUID.randomUUID()), name, description, script, units, defaultMagnitude);
        repo.addEigenfield(eigen);
        return eigen;
    }
}
