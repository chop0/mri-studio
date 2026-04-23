package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;

/** Z-axis phase-map pane. */
public class PhaseMapZPane extends AbstractHeatMapPane {
    public PhaseMapZPane(PaneContext paneContext) {
        super(paneContext, paneContext.session().phaseMapZ, paneContext.session().derived.phaseMapZ);
    }
}
