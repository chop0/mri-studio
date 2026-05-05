package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectSerialiser;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disk I/O for {@link ProjectState}. Walks the slug-based directory layout
 * (mirroring what {@code ProjectSessionViewModel.saveProject} did), writes
 * each document via {@link ProjectSerialiser} (which now uses
 * {@link AtomicWriter}), and cleans up directories whose corresponding
 * document was removed from the state.
 */
public final class ProjectStateIO {

    private final ProjectSerialiser serialiser = new ProjectSerialiser();

    public ProjectSerialiser serialiser() { return serialiser; }

    public void write(ProjectState state, Path root) throws IOException {
        Files.createDirectories(root);

        var manifest = state.manifest();
        if ("Untitled Project".equals(manifest.name())) {
            manifest = new ProjectManifest(root.getFileName().toString(),
                manifest.layoutFile(), manifest.uiStateFile());
        }
        serialiser.writeManifest(root.resolve("mri-project.toml"), manifest);

        writeDocs(root.resolve("sequences"), "sequence.json",
            state.sequences(), s -> ((SequenceDocument) s).name());
        writeDocs(root.resolve("simulations"), "config.json",
            state.simulations(), s -> ((SimulationConfigDocument) s).name());
        writeDocs(root.resolve("eigenfields"), "eigenfield.json",
            state.eigenfields(), s -> ((EigenfieldDocument) s).name());
        writeDocs(root.resolve("circuits"), "circuit.json",
            state.circuits(), s -> ((CircuitDocument) s).name());
        writeDocs(root.resolve("hardware"), "hardware.json",
            state.hardware(), s -> ((HardwareConfigDocument) s).name());
    }

    public ProjectState read(Path root) throws IOException {
        var manifest = serialiser.readManifest(root.resolve("mri-project.toml"));

        var eigenfields = new LinkedHashMap<ProjectNodeId, EigenfieldDocument>();
        readDocs(root.resolve("eigenfields"), "eigenfield.json", EigenfieldDocument.class,
            eigenfields::put, EigenfieldDocument::id);

        var circuits = new LinkedHashMap<ProjectNodeId, CircuitDocument>();
        readDocs(root.resolve("circuits"), "circuit.json", CircuitDocument.class,
            circuits::put, CircuitDocument::id);

        var simulations = new LinkedHashMap<ProjectNodeId, SimulationConfigDocument>();
        readDocs(root.resolve("simulations"), "config.json", SimulationConfigDocument.class,
            simulations::put, SimulationConfigDocument::id);

        var hardware = new LinkedHashMap<ProjectNodeId, HardwareConfigDocument>();
        readDocs(root.resolve("hardware"), "hardware.json", HardwareConfigDocument.class,
            hardware::put, HardwareConfigDocument::id);

        var sequences = new LinkedHashMap<ProjectNodeId, SequenceDocument>();
        readDocs(root.resolve("sequences"), "sequence.json", SequenceDocument.class,
            sequences::put, SequenceDocument::id);

        return new ProjectState(manifest, sequences, simulations, eigenfields, circuits, hardware);
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private interface NameOf { String name(Object doc); }

    private void writeDocs(Path dir, String filename,
                           Map<ProjectNodeId, ?> map,
                           NameOf nameOf) throws IOException {
        if (map.isEmpty() && !Files.isDirectory(dir)) return;
        Files.createDirectories(dir);
        var slugs = new HashSet<String>();
        for (var entry : map.entrySet()) {
            var doc = entry.getValue();
            if (doc == null) continue;
            var slug = slug(nameOf.name(doc));
            slugs.add(slug);
            serialiser.writeJson(dir.resolve(slug).resolve(filename), doc);
        }
        cleanupDeletedDirs(dir, slugs);
    }

    private interface IdOf<T> { ProjectNodeId id(T doc); }
    private interface DocSink<T> { void accept(ProjectNodeId id, T doc); }

    private <T> void readDocs(Path dir, String filename, Class<T> type,
                              DocSink<T> sink, IdOf<T> idOf) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.walk(dir)) {
            for (var path : files
                    .filter(p -> p.getFileName().toString().equals(filename))
                    .sorted()
                    .toList()) {
                var doc = serialiser.readJson(path, type);
                sink.accept(idOf.id(doc), doc);
            }
        }
    }

    private static void cleanupDeletedDirs(Path parentDir, java.util.Set<String> activeSlugs) throws IOException {
        if (!Files.isDirectory(parentDir)) return;
        try (var dirs = Files.list(parentDir)) {
            for (var dir : dirs.filter(Files::isDirectory).toList()) {
                if (!activeSlugs.contains(dir.getFileName().toString())) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                }
            }
        }
    }

    private static String slug(String value) {
        String collapsed = value == null ? "untitled"
            : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        collapsed = collapsed.replaceAll("^-+", "").replaceAll("-+$", "");
        return collapsed.isBlank() ? "untitled" : collapsed;
    }
}
