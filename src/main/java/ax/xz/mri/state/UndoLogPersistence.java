package ax.xz.mri.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * JSON serialisation for the global undo log.
 *
 * <p>Each {@link Mutation}'s {@code before} / {@code after} can be any record
 * in the project tree. Jackson's default-typing mode tags every polymorphic
 * value with its concrete class, so the round-trip survives any payload
 * type without a hand-maintained type registry.
 *
 * <p>The validator restricts allowed deserialised classes to project-owned
 * packages, so a malicious undo-log file can't materialise arbitrary classes
 * via Jackson's polymorphism.
 */
public final class UndoLogPersistence {

    private static final String FILE = "undo.log";

    private final ObjectMapper mapper;

    public UndoLogPersistence() {
        var validator = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .allowIfSubType("ax.xz.mri.")
            .allowIfSubType("java.")
            .build();
        var instantModule = new SimpleModule()
            .addSerializer(Instant.class, new JsonSerializer<>() {
                @Override
                public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    gen.writeString(value.toString());
                }
            })
            .addDeserializer(Instant.class, new JsonDeserializer<>() {
                @Override
                public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    return Instant.parse(p.getText());
                }
            });
        this.mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Java records have package-private fields by default; tell Jackson
            // to look at the canonical accessor methods instead.
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY)
            .registerModule(instantModule)
            .activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
    }

    /** Wire-format wrapper — serialise the deque as a list. */
    public record LogFile(List<Mutation> entries) {
        public LogFile {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    public void write(Deque<Mutation> log, Path projectRoot) throws IOException {
        var dir = projectRoot.resolve(".mri-studio");
        var path = dir.resolve(FILE);
        var entries = new ArrayList<Mutation>(log);
        var bytes = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(new LogFile(entries));
        AtomicWriter.writeBytes(path, bytes);
    }

    public List<Mutation> read(Path projectRoot) {
        var path = projectRoot.resolve(".mri-studio").resolve(FILE);
        if (!Files.isRegularFile(path)) return List.of();
        try {
            var loaded = mapper.readValue(path.toFile(), LogFile.class);
            return loaded.entries();
        } catch (IOException ex) {
            // Best-effort recovery — a malformed undo log shouldn't prevent
            // the project from opening.
            return List.of();
        }
    }

    /** Replace the timestamp on every entry with {@link Instant#EPOCH} so test
     *  comparisons don't depend on real wall-clock time. Pure utility for tests. */
    public static List<Mutation> stripTimestamps(List<Mutation> in) {
        var out = new ArrayList<Mutation>(in.size());
        for (var m : in) {
            out.add(new Mutation(m.scope(), m.before(), m.after(), m.label(),
                Instant.EPOCH, m.editorId(), m.category()));
        }
        return out;
    }
}
