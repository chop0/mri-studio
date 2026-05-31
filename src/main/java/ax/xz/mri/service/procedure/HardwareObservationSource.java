package ax.xz.mri.service.procedure;

import ax.xz.mri.dsl.BakedSequence;
import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ObservationSource} that hands the baked sequence to a live
 * {@link HardwareDevice} and surfaces whatever the device returns.
 *
 * <p>The script DSL drives sim and hardware through the same interface — a
 * script written against {@link ObservationSource} works either way. The
 * wire-up between the script's pulse representation and the device's I/O is
 * the same flattening the SimDispatcher pipeline already does for live
 * hardware runs.
 */
public record HardwareObservationSource(
    HardwareDevice device,
    String deviceLabel,
    List<SequenceChannel> channelSlots
) implements ObservationSource {

    public HardwareObservationSource {
        if (device == null) throw new IllegalArgumentException("HardwareObservationSource.device must be non-null");
        channelSlots = channelSlots == null ? List.of() : List.copyOf(channelSlots);
    }

    @Override
    public String displayName() {
        return deviceLabel == null || deviceLabel.isBlank() ? "Hardware" : deviceLabel;
    }

    @Override
    public MultiProbeSignalTrace run(BakedSequence seq) {
        if (seq == null || seq.isEmpty()) return MultiProbeSignalTrace.empty();
        double dt = seq.segments().get(0).dt();
        var flat = new ArrayList<PulseStep>();
        for (var p : seq.pulses()) flat.addAll(p.steps());
        try {
            var run = device.run(dt, channelSlots, flat, p -> { });
            return run.probeTraces();
        } catch (HardwareException ex) {
            throw new RuntimeException("Hardware observation failed: " + ex.getMessage(), ex);
        }
    }

}
