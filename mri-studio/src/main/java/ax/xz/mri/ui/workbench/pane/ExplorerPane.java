package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.workbench.CommandId;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.StudioIcons;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;

import java.util.HashSet;
import java.util.Set;

/** IDE-style project explorer over imports, runs, captures, and sequences. */
public final class ExplorerPane extends WorkbenchPane {
    private final TreeView<ExplorerEntry> tree = new TreeView<>();
    private boolean suppressSelectionEvents;

    public ExplorerPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Explorer");
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(ExplorerEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    setTooltip(null);
                    return;
                }
                setText(item.label());
                setGraphic(StudioIcons.create(item.iconKind()));
                setContextMenu(buildContextMenu(item));
                setTooltip(new Tooltip(item.label()));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (suppressSelectionEvents) return;
            if (newValue != null && newValue.getValue() != null && newValue.getValue().nodeId() != null) {
                paneContext.session().project.selectNode(newValue.getValue().nodeId());
            }
        });
        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                var item = tree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue() != null && item.getValue().nodeId() != null) {
                    paneContext.session().project.openNode(item.getValue().nodeId());
                }
                event.consume();
            }
        });
        tree.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                var item = tree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue() != null && item.getValue().nodeId() != null) {
                    paneContext.session().project.openNode(item.getValue().nodeId());
                }
            }
        });
        setPaneContent(tree);
        rebuildTree();
        paneContext.session().project.explorer.structureRevision.addListener((obs, oldValue, newValue) -> rebuildTree());
    }

    private void rebuildTree() {
        // Capture expanded state and current selection before rebuilding.
        var expandedIds = collectExpandedNodeIds(tree.getRoot());
        var selectedNodeId = paneContext.session().project.explorer.selectedNodeId.get();

        suppressSelectionEvents = true;
        try {
            tree.setRoot(ExplorerTreeBuilder.build(paneContext.session().project.repository.get()));
            tree.getRoot().setExpanded(true);
            // Restore expanded state.
            restoreExpandedState(tree.getRoot(), expandedIds);
            // Restore selection.
            if (selectedNodeId != null) {
                selectTreeItemByNodeId(tree.getRoot(), selectedNodeId);
            }
        } finally {
            suppressSelectionEvents = false;
        }
    }

    private Set<String> collectExpandedNodeIds(TreeItem<ExplorerEntry> root) {
        var ids = new HashSet<String>();
        if (root == null) return ids;
        collectExpandedRecursive(root, ids);
        return ids;
    }

    private void collectExpandedRecursive(TreeItem<ExplorerEntry> item, Set<String> ids) {
        if (item.isExpanded()) {
            var entry = item.getValue();
            // Use label as key since synthetic nodes don't have nodeIds.
            ids.add(entry.nodeId() != null ? entry.nodeId().value() : "synthetic:" + entry.label());
        }
        for (var child : item.getChildren()) {
            collectExpandedRecursive(child, ids);
        }
    }

    private void restoreExpandedState(TreeItem<ExplorerEntry> item, Set<String> expandedIds) {
        if (item == null) return;
        var entry = item.getValue();
        String key = entry.nodeId() != null ? entry.nodeId().value() : "synthetic:" + entry.label();
        if (expandedIds.contains(key)) {
            item.setExpanded(true);
        }
        for (var child : item.getChildren()) {
            restoreExpandedState(child, expandedIds);
        }
    }

    private void selectTreeItemByNodeId(TreeItem<ExplorerEntry> item, ProjectNodeId targetId) {
        if (item == null) return;
        var entry = item.getValue();
        if (entry != null && targetId.equals(entry.nodeId())) {
            tree.getSelectionModel().select(item);
            return;
        }
        for (var child : item.getChildren()) {
            selectTreeItemByNodeId(child, targetId);
        }
    }

    private ContextMenu buildContextMenu(ExplorerEntry entry) {
        if (entry.synthetic() || entry.nodeId() == null) return null;
        var menu = new ContextMenu();
        var open = new MenuItem("Open");
        open.setOnAction(event -> paneContext.session().project.openNode(entry.nodeId()));
        menu.getItems().add(open);

        var repo = paneContext.session().project.repository.get();
        var node = repo.node(entry.nodeId());
        if (node != null && switch (node.kind()) {
            case IMPORTED_CAPTURE, CAPTURE, RUN_BOOKMARK, IMPORTED_OPTIMISATION_RUN, OPTIMISATION_RUN, IMPORTED_SCENARIO -> true;
            default -> false;
        }) {
            var promote = new MenuItem("Promote to Sequence");
            promote.setOnAction(event -> {
                paneContext.session().project.selectNode(entry.nodeId());
                paneContext.controller().commandRegistry().execute(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE);
            });
            menu.getItems().add(promote);
        }

        if (node != null && node.kind() == ProjectNodeKind.IMPORT_LINK) {
            var reload = new MenuItem("Reload Import");
            reload.setOnAction(event -> {
                paneContext.session().project.selectNode(entry.nodeId());
                paneContext.controller().commandRegistry().execute(CommandId.RELOAD_FILE);
            });
            menu.getItems().add(reload);
        }

        if (node != null && node.kind() == ProjectNodeKind.SEQUENCE) {
            var rename = new MenuItem("Rename Sequence");
            rename.setOnAction(event -> renameSequence(entry.nodeId()));
            menu.getItems().add(rename);

            var delete = new MenuItem("Delete Sequence");
            delete.setOnAction(event -> {
                paneContext.session().project.selectNode(entry.nodeId());
                paneContext.controller().commandRegistry().execute(CommandId.DELETE_SEQUENCE);
            });
            menu.getItems().add(delete);
        }
        return menu;
    }

    private void renameSequence(ProjectNodeId sequenceId) {
        var repository = paneContext.session().project.repository.get();
        var node = repository.node(sequenceId);
        if (!(node instanceof SequenceDocument sequence)) return;
        var dialog = new TextInputDialog(sequence.name());
        dialog.setTitle("Rename Sequence");
        dialog.setHeaderText("Rename sequence");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(value -> !value.isBlank()).ifPresent(value ->
            paneContext.session().project.renameSequence(sequenceId, value));
    }
}
