package ax.xz.mri.model.scenario;

import ax.xz.mri.model.sequence.PulseSegment;
import java.util.List;
import java.util.Map;

/** A named pulse waveform collection. Keys are iteration indices (stored as strings). */
public record Scenario(Map<String, List<PulseSegment>> pulses) {

    /** Returns pulse keys sorted numerically (falling back to lexicographic). */
    public List<String> iterationKeys() {
        return pulses.keySet().stream()
            .sorted((a, b) -> {
                try {
                    return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            })
            .toList();
    }
}
