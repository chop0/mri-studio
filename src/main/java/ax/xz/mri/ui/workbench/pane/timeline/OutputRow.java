package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.RunContext;
import ax.xz.mri.model.simulation.SignalTrace;

/**
 * A read-only signal-trace row rendered beneath the editable tracks.
 *
 * <p>Each row corresponds to one probe of one run context (sim or hardware) —
 * the user enables them via the editor's Outputs popover. The trace is the
 * device's complex demodulated signal; the renderer plots its magnitude on
 * a tinted lane that's clearly distinct from the editable tracks above.
 */
public record OutputRow(
    String label,
    RunContext context,
    SignalTrace trace
) {}
