package ax.xz.mri.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of everything a {@link Script} produced — the summary string it
 * set via {@link ScriptContext#summary(String)} and the named values it
 * stashed via {@link ScriptContext#put(String, Object)}. Built by the
 * {@link ax.xz.mri.service.procedure.ScriptHarness harness} after
 * {@link Script#run} returns; downstream consumers (test assertions, the
 * standalone runner's stdout dump, future "save run" UI) read it back.
 *
 * <p>Outputs are stored in insertion order so a script that emits a
 * convergence curve via {@code ctx.put("rmseHistory", arr)} and then a
 * final estimate via {@code ctx.put("posteriorB", arr)} sees those keys
 * come back in the same order — handy for serialisation.
 */
public record ScriptResult(String summary, Map<String, Object> outputs) {

    public ScriptResult {
        summary = summary == null ? "" : summary;
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public static ScriptResult empty() {
        return new ScriptResult("", Map.of());
    }

    /** Fetch an output by name, or {@code null} if the script didn't emit it. */
    public Object get(String name) { return outputs.get(name); }

    /** Mutable builder used by the harness as it threads {@code ctx.put} / {@code ctx.summary} calls. */
    public static final class Builder {
        private String summary = "";
        private final Map<String, Object> outputs = new LinkedHashMap<>();

        public synchronized void summary(String s) { this.summary = s == null ? "" : s; }
        public synchronized void put(String name, Object value) {
            if (name == null) return;
            outputs.put(name, value);
        }
        public synchronized ScriptResult build() { return new ScriptResult(summary, outputs); }
    }
}
