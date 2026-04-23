package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;

/** Phase trace pane. */
public class PhaseTracePane extends AbstractTracePlotPane {
    public PhaseTracePane(PaneContext paneContext) {
        super(paneContext, paneContext.session().tracePhase);
    }
}
