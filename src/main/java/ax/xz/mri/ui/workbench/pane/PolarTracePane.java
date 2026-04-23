package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;

/** Polar-angle trace pane. */
public class PolarTracePane extends AbstractTracePlotPane {
    public PolarTracePane(PaneContext paneContext) {
        super(paneContext, paneContext.session().tracePolar);
    }
}
