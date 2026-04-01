package ax.xz.mri.project;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.scenario.Scenario;
import ax.xz.mri.support.TestBlochDataFactory;

import java.io.File;
import java.util.Map;

/** Shared fixtures for project/import tests. */
public final class ProjectTestSupport {
    private ProjectTestSupport() {
    }

    public static BlochData mixedImportDocument() {
        var base = TestBlochDataFactory.sampleDocument();
        return new BlochData(
            base.field(),
            base.iso(),
            Map.of(
                "Alpha Capture", new Scenario(Map.of(
                    "0", TestBlochDataFactory.pulseA()
                )),
                "Beta Run", new Scenario(Map.of(
                    "0", TestBlochDataFactory.pulseA(),
                    "2", TestBlochDataFactory.pulseB(),
                    "5", TestBlochDataFactory.pulseA()
                ))
            )
        );
    }

    public static ImportedProjectBundle importBundle() {
        return new LegacyImportService().importLoaded(new File("/tmp/mixed-import.json"), mixedImportDocument());
    }
}
