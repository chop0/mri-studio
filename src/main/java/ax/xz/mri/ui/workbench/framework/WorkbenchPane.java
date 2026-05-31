package ax.xz.mri.ui.workbench.framework;

import ax.xz.mri.ui.workbench.ContextMenuContributor;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;

/** Base pane chrome with explicit setup methods and no constructor-time overridable calls. */
public abstract class WorkbenchPane extends BorderPane {
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    protected final PaneContext paneContext;
    private final PaneHeader header;
    private final PaneStatusBar statusBar;

    protected WorkbenchPane(PaneContext paneContext) {
        this.paneContext = paneContext;
        getStyleClass().add("workbench-pane");

        header = new PaneHeader(paneContext);
        statusBar = new PaneStatusBar();
        setTop(header);
        setBottom(statusBar);

        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> paneContext.activate());
        paneContext.session().docking.activePaneId.addListener((obs, oldValue, newValue) ->
            pseudoClassStateChanged(ACTIVE, paneId().equals(newValue)));
    }

    public final PaneId paneId() {
        return paneContext.paneId();
    }

    protected final void setPaneTitle(String title) {
        header.setTitle(title);
    }

    protected final void setToolNodes(Node... tools) {
        header.setTools(tools);
    }

    protected final void setPaneContent(Node content) {
        setCenter(content);
    }

    protected final void setHeaderMenuContributor(ContextMenuContributor contributor) {
        header.setContextMenuContributor(contributor);
    }

    protected final void setPaneStatus(String text) {
        statusBar.setText(text);
        paneContext.publishStatus(text);
    }

    /**
     * Status with multiple fields rendered separated by a vertical
     * {@link javafx.scene.control.Separator} UI element. Project convention is
     * to use this rather than baking a unicode separator character into a
     * single status string.
     */
    protected final void setPaneStatus(String... segments) {
        statusBar.setSegments(segments);
        // Publish a joined-text fallback so the shell status mirrors the
        // pane status; the shell strip uses a single string.
        var joined = new StringBuilder();
        if (segments != null) {
            for (var seg : segments) {
                if (seg == null || seg.isEmpty()) continue;
                if (joined.length() > 0) joined.append("   ");
                joined.append(seg);
            }
        }
        paneContext.publishStatus(joined.toString());
    }

    public void dispose() {
    }
}
