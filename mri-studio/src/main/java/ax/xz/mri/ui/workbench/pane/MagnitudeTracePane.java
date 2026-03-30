package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;

/** |M⊥| trace pane. */
public class MagnitudeTracePane extends AbstractTracePlotPane {
    public MagnitudeTracePane(PaneContext paneContext) {
        super(paneContext, paneContext.session().traceMagnitude);
    }
}
