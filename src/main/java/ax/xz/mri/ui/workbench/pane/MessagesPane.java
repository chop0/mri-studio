package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.viewmodel.MessagesViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Displays the stream of user-facing messages (info, warnings, errors) from the session.
 *
 * <p>The list autoscrolls to the latest entry. Selecting an entry reveals its full
 * detail body (stack trace, long-form text) in the lower pane.
 */
public final class MessagesPane extends WorkbenchPane {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ListView<MessagesViewModel.Message> listView = new ListView<>();
    private final TextArea details = new TextArea();
    private final MessagesViewModel viewModel;
    private final ListChangeListener<MessagesViewModel.Message> listListener;

    public MessagesPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Messages");
        this.viewModel = paneContext.session().messages;

        listView.setItems(viewModel.messages());
        listView.setCellFactory(lv -> new MessageCell());
        listView.setPlaceholder(new Label("No messages yet."));
        listView.getStyleClass().add("messages-list");

        details.setEditable(false);
        details.setWrapText(false);
        details.setPromptText("Select a message to view its details.");
        details.getStyleClass().add("messages-details");
        details.setStyle("-fx-font-family: monospace; -fx-font-size: 10.5;");

        listView.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) {
                details.setText("");
                return;
            }
            var body = new StringBuilder();
            body.append('[').append(n.level()).append("] ");
            if (n.source() != null && !n.source().isEmpty()) body.append(n.source()).append(": ");
            body.append(n.summary());
            if (n.details() != null && !n.details().isEmpty()) {
                body.append("\n\n").append(n.details());
            }
            details.setText(body.toString());
            details.positionCaret(0);
        });

        // Clear + copy buttons live in the pane's tool strip (top-right).
        var clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> viewModel.clear());
        var copyBtn = new Button("Copy");
        copyBtn.setOnAction(e -> copySelectedToClipboard());
        setToolNodes(clearBtn, copyBtn);

        var split = new SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().addAll(listView, details);
        split.setDividerPositions(0.6);
        SplitPane.setResizableWithParent(listView, true);
        SplitPane.setResizableWithParent(details, true);

        var root = new VBox();
        VBox.setVgrow(split, Priority.ALWAYS);
        root.getChildren().add(split);
        setPaneContent(root);

        listListener = change -> {
            while (change.next()) {
                if (change.wasAdded() && !viewModel.messages().isEmpty()) {
                    listView.scrollTo(viewModel.messages().size() - 1);
                }
            }
            updateStatus();
        };
        viewModel.messages().addListener(listListener);
        updateStatus();
    }

    private void copySelectedToClipboard() {
        var text = details.getText();
        if (text == null || text.isEmpty()) return;
        var clip = new ClipboardContent();
        clip.putString(text);
        Clipboard.getSystemClipboard().setContent(clip);
    }

    private void updateStatus() {
        int total = viewModel.messages().size();
        long errors = viewModel.messages().stream()
            .filter(m -> m.level() == MessagesViewModel.Level.ERROR).count();
        long warns = viewModel.messages().stream()
            .filter(m -> m.level() == MessagesViewModel.Level.WARN).count();
        if (total == 0) {
            setPaneStatus("No messages");
        } else {
            setPaneStatus(total + " message" + (total == 1 ? "" : "s")
                + " — " + errors + " error" + (errors == 1 ? "" : "s")
                + ", " + warns + " warning" + (warns == 1 ? "" : "s"));
        }
    }

    @Override
    public void dispose() {
        viewModel.messages().removeListener(listListener);
        super.dispose();
    }

    private static final class MessageCell extends ListCell<MessagesViewModel.Message> {
        private final Label timeLabel = new Label();
        private final Label levelLabel = new Label();
        private final Label sourceLabel = new Label();
        private final Label summaryLabel = new Label();
        private final HBox root;

        MessageCell() {
            timeLabel.setStyle("-fx-text-fill: #6b7784; -fx-font-family: monospace; -fx-font-size: 10;");
            timeLabel.setMinWidth(Region.USE_PREF_SIZE);

            levelLabel.setMinWidth(44);
            levelLabel.setAlignment(Pos.CENTER);
            levelLabel.setStyle("-fx-font-size: 9; -fx-font-weight: bold; -fx-padding: 1 4 1 4;");

            sourceLabel.setStyle("-fx-text-fill: #425563; -fx-font-size: 10.5; -fx-font-weight: bold;");
            sourceLabel.setMinWidth(Region.USE_PREF_SIZE);

            summaryLabel.setStyle("-fx-font-size: 10.5;");
            summaryLabel.setWrapText(true);
            HBox.setHgrow(summaryLabel, Priority.ALWAYS);

            root = new HBox(6, timeLabel, levelLabel, sourceLabel, summaryLabel);
            root.setAlignment(Pos.TOP_LEFT);
            root.setPadding(new Insets(2, 4, 2, 4));
        }

        @Override
        protected void updateItem(MessagesViewModel.Message item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            timeLabel.setText(TIME_FORMAT.format(item.timestamp()));
            levelLabel.setText(item.level().name());
            switch (item.level()) {
                case ERROR -> levelLabel.setStyle(
                    "-fx-background-color: #fdecea; -fx-text-fill: #b3261e;"
                    + " -fx-background-radius: 3; -fx-font-size: 9;"
                    + " -fx-font-weight: bold; -fx-padding: 1 4 1 4;");
                case WARN -> levelLabel.setStyle(
                    "-fx-background-color: #fff4e5; -fx-text-fill: #a86200;"
                    + " -fx-background-radius: 3; -fx-font-size: 9;"
                    + " -fx-font-weight: bold; -fx-padding: 1 4 1 4;");
                case INFO -> levelLabel.setStyle(
                    "-fx-background-color: #e8f0fe; -fx-text-fill: #1a56b5;"
                    + " -fx-background-radius: 3; -fx-font-size: 9;"
                    + " -fx-font-weight: bold; -fx-padding: 1 4 1 4;");
            }
            sourceLabel.setText(item.source() == null || item.source().isEmpty() ? "" : item.source() + ":");
            sourceLabel.setManaged(!sourceLabel.getText().isEmpty());
            sourceLabel.setVisible(sourceLabel.getText() != null && !sourceLabel.getText().isEmpty());
            summaryLabel.setText(firstLine(item.summary()));
            setGraphic(root);
            setText(null);
        }

        private static String firstLine(String text) {
            if (text == null) return "";
            int nl = text.indexOf('\n');
            return nl < 0 ? text : text.substring(0, nl);
        }
    }

}
