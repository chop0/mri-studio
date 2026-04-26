package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Stores the latest workbench layout in the user's home directory. */
public class PersistentLayoutStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;

    public PersistentLayoutStore(Path path) {
        this.path = path;
    }

    public Optional<WorkbenchLayoutState> load() {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), WorkbenchLayoutState.class));
        } catch (IOException ignored) {
            // Corrupt or schema-mismatched layout file — fall back to a fresh default layout.
            return Optional.empty();
        }
    }

    public void save(WorkbenchLayoutState state) throws IOException {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ), state);
    }
}
