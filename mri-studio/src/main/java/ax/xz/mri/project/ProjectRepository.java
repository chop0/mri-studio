package ax.xz.mri.project;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory semantic project repository over imported and project-owned nodes. */
public final class ProjectRepository {
    private ProjectManifest manifest;
    private final Map<ProjectNodeId, ProjectNode> nodes = new LinkedHashMap<>();
    private final Map<ProjectNodeId, List<ProjectNodeId>> children = new LinkedHashMap<>();
    private final Map<ProjectNodeId, ProjectNodeId> parent = new LinkedHashMap<>();
    private final Map<ProjectNodeId, ImportIndexDocument> importIndexes = new LinkedHashMap<>();
    private final Map<ProjectNodeId, LoadedImportState> loadedImports = new LinkedHashMap<>();
    private final List<ProjectNodeId> importLinkIds = new ArrayList<>();
    private final List<ProjectNodeId> sequenceIds = new ArrayList<>();

    public ProjectRepository(ProjectManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest);
    }

    public static ProjectRepository untitled() {
        return new ProjectRepository(new ProjectManifest("Untitled Project", ".mri-studio/layout.json", ".mri-studio/ui-state.json"));
    }

    public ProjectManifest manifest() {
        return manifest;
    }

    public void setManifest(ProjectManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest);
    }

    public List<ProjectNodeId> importLinkIds() {
        return List.copyOf(importLinkIds);
    }

    public List<ProjectNodeId> sequenceIds() {
        return List.copyOf(sequenceIds);
    }

    public ProjectNode node(ProjectNodeId id) {
        return nodes.get(id);
    }

    public Optional<ProjectNode> maybeNode(ProjectNodeId id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<ProjectNodeId> childrenOf(ProjectNodeId id) {
        return List.copyOf(children.getOrDefault(id, List.of()));
    }

    public ProjectNodeId parentOf(ProjectNodeId id) {
        return parent.get(id);
    }

    public void addImportBundle(ImportedProjectBundle bundle) {
        replaceImportBundle(bundle);
    }

    public void replaceImportBundle(ImportedProjectBundle bundle) {
        removeImport(bundle.importLink().id());
        addImportNode(bundle.importLink());
        importIndexes.put(bundle.importLink().id(), bundle.importIndex());
        loadedImports.put(bundle.importLink().id(), new LoadedImportState(bundle.sourceFile(), bundle.blochData()));
        for (var entry : bundle.nodes().entrySet()) {
            if (entry.getKey().equals(bundle.importLink().id())) continue;
            nodes.put(entry.getKey(), entry.getValue());
        }
        for (var entry : bundle.children().entrySet()) {
            var orderedChildren = new ArrayList<>(entry.getValue());
            children.put(entry.getKey(), orderedChildren);
            for (var childId : orderedChildren) {
                parent.put(childId, entry.getKey());
            }
        }
    }

    public void addSequence(SequenceDocument sequence) {
        nodes.put(sequence.id(), sequence);
        children.putIfAbsent(sequence.id(), new ArrayList<>());
        if (!sequenceIds.contains(sequence.id())) {
            sequenceIds.add(sequence.id());
        }
    }

    public SequenceDocument promoteSnapshotToSequence(ProjectNodeId snapshotId, String preferredName) {
        var snapshot = sequenceSnapshot(snapshotId);
        String name = preferredName == null || preferredName.isBlank()
            ? snapshot.name().replace("Snapshot", "").trim() + " Sequence"
            : preferredName;
        var sequence = new SequenceDocument(
            new ProjectNodeId("seq-" + java.util.UUID.randomUUID()),
            name,
            snapshot.segments(),
            snapshot.pulse()
        );
        addSequence(sequence);
        return sequence;
    }

    public SequenceDocument renameSequence(ProjectNodeId sequenceId, String newName) {
        var node = nodes.get(sequenceId);
        if (!(node instanceof SequenceDocument sequence)) {
            throw new IllegalArgumentException("Node " + sequenceId + " is not a sequence");
        }
        var renamed = new SequenceDocument(sequence.id(), newName, sequence.segments(), sequence.pulse());
        nodes.put(sequenceId, renamed);
        return renamed;
    }

    public ProjectNodeId containingImport(ProjectNodeId id) {
        ProjectNodeId cursor = id;
        while (cursor != null) {
            if (nodes.get(cursor) instanceof ImportLinkDocument) return cursor;
            cursor = parent.get(cursor);
        }
        return null;
    }

    public ActiveCapture resolveCapture(ProjectNodeId captureId) {
        var node = nodes.get(captureId);
        if (node instanceof ImportedCaptureDocument capture) {
            var loaded = loadedImports.get(capture.importLinkId());
            if (loaded == null) return null;
            var snapshot = sequenceSnapshot(capture.sequenceSnapshotId());
            return new ActiveCapture(
                capture.id(),
                snapshot.id(),
                capture.name(),
                loaded.sourceFile(),
                loaded.blochData(),
                loaded.blochData().field(),
                capture.sourceScenarioName(),
                capture.iterationKey(),
                snapshot.segments(),
                snapshot.pulse(),
                true
            );
        }
        if (node instanceof CaptureDocument capture) {
            var snapshot = sequenceSnapshot(capture.sequenceSnapshotId());
            return new ActiveCapture(
                capture.id(),
                snapshot.id(),
                capture.name(),
                null,
                null,
                null,
                null,
                capture.iterationKey(),
                snapshot.segments(),
                snapshot.pulse(),
                false
            );
        }
        if (node instanceof SequenceSnapshotDocument snapshot) {
            ProjectNodeId parentCaptureId = snapshot.parentCaptureId();
            return parentCaptureId == null ? null : resolveCapture(parentCaptureId);
        }
        return null;
    }

    public SequenceSnapshotDocument resolveSnapshot(ProjectNodeId nodeId, ProjectNodeId runActiveCaptureId) {
        var node = nodes.get(nodeId);
        if (node instanceof SequenceSnapshotDocument snapshot) return snapshot;
        if (node instanceof ImportedCaptureDocument capture) return sequenceSnapshot(capture.sequenceSnapshotId());
        if (node instanceof CaptureDocument capture) return sequenceSnapshot(capture.sequenceSnapshotId());
        if (node instanceof RunBookmarkDocument bookmark) return resolveSnapshot(bookmark.targetCaptureId(), runActiveCaptureId);
        if ((node instanceof ImportedOptimisationRunDocument || node instanceof OptimisationRunDocument) && runActiveCaptureId != null) {
            return resolveSnapshot(runActiveCaptureId, null);
        }
        if (node instanceof ImportedScenarioDocument scenario) {
            if (scenario.iterative() && scenario.importedRunId() != null && runActiveCaptureId != null) {
                return resolveSnapshot(runActiveCaptureId, null);
            }
            if (!scenario.iterative() && !scenario.directCaptureIds().isEmpty()) {
                return resolveSnapshot(scenario.directCaptureIds().getFirst(), null);
            }
        }
        return null;
    }

    public List<ProjectNodeId> captureIdsForRun(ProjectNodeId runId) {
        var node = nodes.get(runId);
        if (node instanceof ImportedOptimisationRunDocument run) return run.captureIds();
        if (node instanceof OptimisationRunDocument run) return run.captureIds();
        return List.of();
    }

    public List<RunBookmarkDocument> bookmarksForRun(ProjectNodeId runId) {
        return childrenOf(runId).stream()
            .map(nodes::get)
            .filter(RunBookmarkDocument.class::isInstance)
            .map(RunBookmarkDocument.class::cast)
            .toList();
    }

    public ProjectNodeId defaultCaptureForRun(ProjectNodeId runId) {
        var node = nodes.get(runId);
        if (node instanceof ImportedOptimisationRunDocument run) return run.latestCaptureId();
        if (node instanceof OptimisationRunDocument run) {
            return run.latestCaptureId() != null ? run.latestCaptureId() : run.firstCaptureId();
        }
        return null;
    }

    public ProjectNodeId firstOpenableNodeForImport(ProjectNodeId importLinkId) {
        for (var scenarioId : importIndexes.getOrDefault(importLinkId, new ImportIndexDocument(
            new ProjectNodeId("missing"),
            importLinkId,
            List.of()
        )).scenarioIds()) {
            return scenarioId;
        }
        return importLinkId;
    }

    public ImportIndexDocument importIndex(ProjectNodeId importLinkId) {
        return importIndexes.get(importLinkId);
    }

    public boolean contains(ProjectNodeId id) {
        return nodes.containsKey(id);
    }

    private SequenceSnapshotDocument sequenceSnapshot(ProjectNodeId id) {
        var node = nodes.get(id);
        if (!(node instanceof SequenceSnapshotDocument snapshot)) {
            throw new IllegalArgumentException("Node " + id + " is not a sequence snapshot");
        }
        return snapshot;
    }

    private void addImportNode(ImportLinkDocument link) {
        nodes.put(link.id(), link);
        children.putIfAbsent(link.id(), new ArrayList<>());
        if (!importLinkIds.contains(link.id())) {
            importLinkIds.add(link.id());
        }
    }

    private void removeImport(ProjectNodeId importId) {
        if (importId == null || !nodes.containsKey(importId)) return;
        Deque<ProjectNodeId> stack = new ArrayDeque<>();
        stack.push(importId);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            for (var child : new ArrayList<>(children.getOrDefault(current, List.of()))) {
                stack.push(child);
            }
            children.remove(current);
            parent.remove(current);
            nodes.remove(current);
            sequenceIds.remove(current);
        }
        importIndexes.remove(importId);
        loadedImports.remove(importId);
        importLinkIds.remove(importId);
        parent.entrySet().removeIf(entry -> entry.getValue().equals(importId));
    }

    private record LoadedImportState(File sourceFile, BlochData blochData) {
    }
}
