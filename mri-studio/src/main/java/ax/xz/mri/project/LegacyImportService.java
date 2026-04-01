package ax.xz.mri.project;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.service.io.BlochDataReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Converts legacy combined JSON viewer documents into explicit project-side import nodes. */
public final class LegacyImportService {
    public ImportedProjectBundle importFile(File file) throws IOException {
        BlochData data = BlochDataReader.read(file);
        return importLoaded(file, data);
    }

    public ImportedProjectBundle importLoaded(File file, BlochData data) {
        String importSeed = file.getAbsoluteFile().toPath().normalize().toString();
        var importLinkId = stableId("import", importSeed);
        var importLink = new ImportLinkDocument(importLinkId, stripExtension(file.getName()), file.getAbsolutePath(), ReloadMode.MANUAL);
        var nodeMap = new LinkedHashMap<ProjectNodeId, ProjectNode>();
        var childMap = new LinkedHashMap<ProjectNodeId, List<ProjectNodeId>>();
        nodeMap.put(importLinkId, importLink);
        childMap.put(importLinkId, new ArrayList<>());

        var scenarioIds = new ArrayList<ProjectNodeId>();
        var segments = List.copyOf(data.field() == null || data.field().segments == null ? List.<Segment>of() : data.field().segments);

        for (var entry : data.scenarios().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String scenarioName = entry.getKey();
            var scenario = entry.getValue();
            var scenarioId = stableId("scenario", importSeed, scenarioName);
            var iterationKeys = scenario.iterationKeys();
            boolean iterative = iterationKeys.size() > 1;
            ProjectNodeId runId = iterative ? stableId("run", importSeed, scenarioName) : null;
            var directCaptureIds = new ArrayList<ProjectNodeId>();

            if (iterative) {
                var captureIds = new ArrayList<ProjectNodeId>();
                for (String iterationKey : iterationKeys) {
                    var captureId = stableId("capture", importSeed, scenarioName, iterationKey);
                    var snapshotId = stableId("snapshot", importSeed, scenarioName, iterationKey);
                    captureIds.add(captureId);
                    nodeMap.put(captureId, new ImportedCaptureDocument(
                        captureId,
                        importLinkId,
                        scenarioId,
                        runId,
                        scenarioName + " " + iterationKey,
                        scenarioName,
                        iterationKey,
                        snapshotId
                    ));
                    nodeMap.put(snapshotId, new SequenceSnapshotDocument(
                        snapshotId,
                        scenarioName + " Snapshot " + iterationKey,
                        captureId,
                        segments,
                        copyPulse(scenario.pulses().get(iterationKey))
                    ));
                    childMap.put(captureId, List.of(snapshotId));
                }

                var firstCaptureId = captureIds.get(0);
                var lastCaptureId = captureIds.get(captureIds.size() - 1);
                nodeMap.put(runId, new ImportedOptimisationRunDocument(
                    runId,
                    importLinkId,
                    scenarioId,
                    scenarioName,
                    captureIds,
                    firstCaptureId,
                    lastCaptureId,
                    null,
                    List.of()
                ));
                var firstBookmarkId = stableId("bookmark-first", importSeed, scenarioName);
                var lastBookmarkId = stableId("bookmark-latest", importSeed, scenarioName);
                nodeMap.put(firstBookmarkId, new RunBookmarkDocument(
                    firstBookmarkId,
                    "First Iteration",
                    runId,
                    firstCaptureId,
                    BookmarkKind.FIRST,
                    iterationKeys.get(0)
                ));
                nodeMap.put(lastBookmarkId, new RunBookmarkDocument(
                    lastBookmarkId,
                    "Last Iteration",
                    runId,
                    lastCaptureId,
                    BookmarkKind.LAST,
                    iterationKeys.get(iterationKeys.size() - 1)
                ));
                childMap.put(runId, List.of(firstBookmarkId, lastBookmarkId));
            } else {
                String iterationKey = iterationKeys.isEmpty() ? "0" : iterationKeys.get(0);
                var captureId = stableId("capture", importSeed, scenarioName, iterationKey);
                var snapshotId = stableId("snapshot", importSeed, scenarioName, iterationKey);
                directCaptureIds.add(captureId);
                nodeMap.put(captureId, new ImportedCaptureDocument(
                    captureId,
                    importLinkId,
                    scenarioId,
                    null,
                    scenarioName,
                    scenarioName,
                    iterationKey,
                    snapshotId
                ));
                nodeMap.put(snapshotId, new SequenceSnapshotDocument(
                    snapshotId,
                    scenarioName + " Snapshot",
                    captureId,
                    segments,
                    copyPulse(scenario.pulses().get(iterationKey))
                ));
                childMap.put(captureId, List.of(snapshotId));
            }

            nodeMap.put(scenarioId, new ImportedScenarioDocument(
                scenarioId,
                importLinkId,
                scenarioName,
                scenarioName,
                iterative,
                iterationKeys,
                runId,
                directCaptureIds
            ));
            var scenarioChildren = iterative ? List.of(runId) : List.copyOf(directCaptureIds);
            childMap.put(scenarioId, scenarioChildren);
            childMap.get(importLinkId).add(scenarioId);
            scenarioIds.add(scenarioId);
        }

        return new ImportedProjectBundle(
            importLink,
            new ImportIndexDocument(stableId("index", importSeed), importLinkId, scenarioIds),
            nodeMap,
            childMap,
            file,
            data
        );
    }

    private static List<PulseSegment> copyPulse(List<PulseSegment> pulse) {
        return pulse.stream().map(segment -> new PulseSegment(segment.steps().stream()
            .map(step -> new ax.xz.mri.model.sequence.PulseStep(step.b1x(), step.b1y(), step.gx(), step.gz(), step.rfGate()))
            .toList())).toList();
    }

    private static ProjectNodeId stableId(String prefix, String... parts) {
        String seed = String.join("|", parts);
        return new ProjectNodeId(prefix + "-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }
}
