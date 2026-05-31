package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectSerialiser;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.project.SubstanceDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        writeDocs(root.resolve("substances"), "substance.json",
            state.substances(), s -> ((SubstanceDocument) s).name());
        writeProcedureSources(root.resolve("procedures"), state.procedures());
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

        var substances = new LinkedHashMap<ProjectNodeId, SubstanceDocument>();
        readDocs(root.resolve("substances"), "substance.json", SubstanceDocument.class,
            substances::put, SubstanceDocument::id);

        var procedures = readProcedureSources(root.resolve("procedures"));

        return new ProjectState(manifest, sequences, simulations, eigenfields, circuits, hardware, substances, procedures);
    }

    /* ── Procedure source files (procedures/<Name>.java) ──────────────────
     *
     * Procedures persist as ordinary .java compilation units rather than
     * JSON-wrapped strings so they (a) open directly in IntelliJ with
     * full autocomplete and (b) survive single-file launching via the
     * JDK source-code launcher (JEP 330) against the studio's jlink
     * image. Metadata is derived at load time: name comes from the file
     * basename; id from a slug of that name. The runtime contract is
     * {@link ax.xz.mri.dsl.Script}, but we don't compile-on-load any
     * more — the user-visible explorer only needs name + source.
     */

    private void writeProcedureSources(Path dir, Map<ProjectNodeId, ProcedureDocument> procedures) throws IOException {
        if (procedures.isEmpty() && !Files.isDirectory(dir)) return;
        Files.createDirectories(dir);
        var kept = new HashSet<String>();
        for (var doc : procedures.values()) {
            if (doc == null) continue;
            String fileName = procedureFileName(doc.name());
            kept.add(fileName);
            AtomicWriter.writeString(dir.resolve(fileName), doc.source());
        }
        // Drop .java files for procedures that have been deleted from state.
        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                if (Files.isRegularFile(file)
                    && file.getFileName().toString().endsWith(".java")
                    && !kept.contains(file.getFileName().toString())) {
                    try { Files.delete(file); } catch (IOException ignored) {}
                }
            }
        }
    }

    private LinkedHashMap<ProjectNodeId, ProcedureDocument> readProcedureSources(Path dir) throws IOException {
        var procedures = new LinkedHashMap<ProjectNodeId, ProcedureDocument>();
        if (!Files.isDirectory(dir)) return procedures;
        List<Path> javaFiles;
        try (var stream = Files.list(dir)) {
            javaFiles = stream
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
        for (var path : javaFiles) {
            var doc = loadProcedureFile(path);
            if (doc != null) procedures.put(doc.id(), doc);
        }
        return procedures;
    }

    /**
     * Read a single procedure source file into a {@link ProcedureDocument}.
     * The name is derived from the filename; the source is read verbatim.
     * No on-load compilation — the editor pane recompiles on demand and
     * surfaces any failures inline.
     */
    private static ProcedureDocument loadProcedureFile(Path file) throws IOException {
        var source = Files.readString(file);
        if (source.isBlank()) return null;
        var baseName = file.getFileName().toString();
        baseName = baseName.substring(0, baseName.length() - ".java".length());
        return new ProcedureDocument(
            new ProjectNodeId("proc-" + slug(baseName)),
            baseName, source);
    }

    /**
     * Filename to use for a procedure with the given display name. Identifier-
     * sanitised so {@code procedures/} doubles as a Java source directory:
     * IntelliJ + javac both refuse to compile files whose stem isn't a valid
     * Java identifier, so we rewrite the name into camel-case here.
     */
    static String procedureFileName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "Procedure.java";
        var sb = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < displayName.length(); i++) {
            char c = displayName.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        var stem = sb.length() == 0 ? "Procedure" : sb.toString();
        if (!Character.isJavaIdentifierStart(stem.charAt(0))) stem = "P" + stem;
        return stem + ".java";
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

    private static void cleanupDeletedDirs(Path parentDir, Set<String> activeSlugs) throws IOException {
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
