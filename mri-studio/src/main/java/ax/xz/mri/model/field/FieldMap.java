package ax.xz.mri.model.field;

import ax.xz.mri.model.sequence.Segment;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Spatial field map loaded from the {@code "field"} key of bloch_data.json.
 * All arrays are indexed [r][z].
 */
public class FieldMap {
    @JsonProperty("r_mm")       public double[]   rMm;
    @JsonProperty("z_mm")       public double[]   zMm;
    @JsonProperty("B0n")        public double     b0n;
    @JsonProperty("dBz_uT")     public double[][] dBzUt;
    @JsonProperty("Mx0")        public double[][] mx0;
    @JsonProperty("My0")        public double[][] my0;
    @JsonProperty("Mz0")        public double[][] mz0;
    @JsonProperty("FOV_X")      public double     fovX;
    @JsonProperty("FOV_Z")      public double     fovZ;
    public double               gamma;
    @JsonProperty("T1")         public double     t1;
    @JsonProperty("T2")         public double     t2;
    public List<Segment>        segments;
    @JsonProperty("slice_half") public Double     sliceHalf;  // metres; null → 5 mm
}
