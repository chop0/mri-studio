package ax.xz.mri.model.scenario;

import ax.xz.mri.model.field.FieldMap;

/** Precomputed spatial state consumed by the Bloch simulator. */
public record BlochData(FieldMap field) {}
