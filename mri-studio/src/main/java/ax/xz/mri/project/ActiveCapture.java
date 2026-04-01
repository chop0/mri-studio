package ax.xz.mri.project;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;

import java.io.File;
import java.util.List;

/** Resolved analysis object currently feeding the capture workspace. */
public record ActiveCapture(
    ProjectNodeId captureId,
    ProjectNodeId snapshotId,
    String name,
    File sourceFile,
    BlochData blochData,
    FieldMap field,
    String scenarioName,
    String iterationKey,
    List<Segment> segments,
    List<PulseSegment> pulse,
    boolean imported
) {
    public ActiveCapture {
        segments = List.copyOf(segments);
        pulse = List.copyOf(pulse);
    }
}
