package ax.xz.mri.ui.viewmodel;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * Central sink for user-facing status, warning, and error messages from
 * simulation, project, and other long-running operations. Displayed by
 * {@link ax.xz.mri.ui.workbench.pane.MessagesPane}.
 */
public final class MessagesViewModel {
    public enum Level { INFO, WARN, ERROR }

    public record Message(Instant timestamp, Level level, String source, String summary, String details) {
        public Message {
            if (timestamp == null) timestamp = Instant.now();
            if (level == null) level = Level.INFO;
            if (source == null) source = "";
            if (summary == null) summary = "";
        }
    }

    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<Message> readOnly = FXCollections.unmodifiableObservableList(messages);

    public ObservableList<Message> messages() {
        return readOnly;
    }

    public void logInfo(String source, String summary) {
        post(new Message(Instant.now(), Level.INFO, source, summary, null));
    }

    public void logWarning(String source, String summary) {
        post(new Message(Instant.now(), Level.WARN, source, summary, null));
    }

    public void logError(String source, String summary, Throwable cause) {
        post(new Message(Instant.now(), Level.ERROR, source, summary, formatStackTrace(cause)));
    }

    public void clear() {
        if (Platform.isFxApplicationThread()) messages.clear();
        else Platform.runLater(messages::clear);
    }

    private void post(Message message) {
        if (Platform.isFxApplicationThread()) messages.add(message);
        else Platform.runLater(() -> messages.add(message));
    }

    private static String formatStackTrace(Throwable cause) {
        if (cause == null) return null;
        var sw = new StringWriter();
        try (var pw = new PrintWriter(sw)) {
            cause.printStackTrace(pw);
        }
        return sw.toString();
    }
}
