package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;

/** R-axis phase-map pane. */
public class PhaseMapRPane extends AbstractHeatMapPane {
    public PhaseMapRPane(PaneContext paneContext) {
        super(paneContext, paneContext.session().phaseMapR, paneContext.session().derived.phaseMapR);
    }
}
