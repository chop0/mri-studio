package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.ui.model.IsochromatCollectionModel;
import ax.xz.mri.ui.model.IsochromatSelectionModel;

/** Shared browser model for the points/isochromats table. */
public record PointsViewModel(
    IsochromatCollectionModel collection,
    IsochromatSelectionModel selection
) {
}
