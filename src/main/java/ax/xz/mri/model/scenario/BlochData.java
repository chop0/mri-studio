package ax.xz.mri.model.scenario;

import ax.xz.mri.model.field.FieldMap;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Precomputed spatial state consumed by the Bloch simulator. */
public record BlochData(@JsonProperty("field") FieldMap field) {}
