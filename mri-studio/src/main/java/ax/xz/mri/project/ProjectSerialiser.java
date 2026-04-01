package ax.xz.mri.project;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Simple project manifest/import-link TOML plus JSON document serialiser. */
public final class ProjectSerialiser {
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void writeManifest(Path path, ProjectManifest manifest) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, """
            schema = 1
            name = "%s"
            layout_file = "%s"
            ui_state_file = "%s"
            """.formatted(
            escape(manifest.name()),
            escape(manifest.layoutFile()),
            escape(manifest.uiStateFile())
        ));
    }

    public ProjectManifest readManifest(Path path) throws IOException {
        var values = parseSimpleToml(path);
        return new ProjectManifest(
            values.getOrDefault("name", "Untitled Project"),
            values.getOrDefault("layout_file", ".mri-studio/layout.json"),
            values.getOrDefault("ui_state_file", ".mri-studio/ui-state.json")
        );
    }

    public void writeImportLink(Path path, ImportLinkDocument link) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, """
            schema = 1
            id = "%s"
            name = "%s"
            source = "%s"
            reload_mode = "%s"
            """.formatted(
            escape(link.id().value()),
            escape(link.name()),
            escape(link.sourcePath()),
            escape(link.reloadMode().name())
        ));
    }

    public ImportLinkDocument readImportLink(Path path) throws IOException {
        var values = parseSimpleToml(path);
        return new ImportLinkDocument(
            new ProjectNodeId(values.get("id")),
            values.get("name"),
            values.get("source"),
            ReloadMode.valueOf(values.getOrDefault("reload_mode", "MANUAL"))
        );
    }

    public <T> void writeJson(Path path, T document) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), document);
    }

    public <T> T readJson(Path path, Class<T> type) throws IOException {
        return mapper.readValue(path.toFile(), type);
    }

    private static Map<String, String> parseSimpleToml(Path path) throws IOException {
        var values = new LinkedHashMap<String, String>();
        for (var rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            if (split < 0) continue;
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"");
            }
            values.put(key, value);
        }
        return values;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }
}
