package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipKind;

/**
 * A drag gesture in progress on the timeline canvas.
 *
 * <p>Every in-flight interaction is one of these sealed records. Replacing
 * the previous nine-integer-constant state machine means the compiler
 * enforces that new drag states get handled everywhere, and each state
 * carries exactly the fields it needs instead of sharing a pile of
 * loosely-named doubles.
 */
public sealed interface ClipDragState {

    /** No drag in progress. */
    Idle IDLE = new Idle();

    /** No drag in progress. */
    record Idle() implements ClipDragState {}

    /** Dragging one or more selected clips horizontally (and possibly cross-track). */
    record Move(String primaryClipId,
                double anchorOffsetMicros,
                int originTrackIndex,
                boolean multi,
                double multiAnchorTimeMicros) implements ClipDragState {}

    /** Dragging the left trim edge of a clip. */
    record ResizeLeft(String clipId) implements ClipDragState {}

    /** Dragging the right trim edge of a clip. */
    record ResizeRight(String clipId) implements ClipDragState {}

    /** Alt-drag on the clip body: adjust amplitude. */
    record Amplitude(String clipId,
                     double anchorY,
                     int trackIndex,
                     double originalAmplitude) implements ClipDragState {}

    /** Dragging a single spline control point. */
    record SplinePoint(String clipId, int pointIndex) implements ClipDragState {}

    /** Middle-mouse or Alt-drag on empty space: pan the viewport. */
    record PanView(double anchorTimeMicros, double viewStartAnchorMicros) implements ClipDragState {}

    /** Click-drag on an empty track with a creation tool selected: rubber-band a new clip. */
    record CreateClip(ClipKind kind,
                      String trackId,
                      double startTimeMicros,
                      double currentX) implements ClipDragState {}

    /** Empty-space click-drag: rubber-band selection. */
    record RubberBand(double startX, double startY, double endX, double endY) implements ClipDragState {}

    /**
     * Dragging the divider above the read-only output band to resize the
     * per-row output trace lanes. Captured at press time: the mouse's Y
     * pixel position and the row count, so each drag delta translates 1:1
     * into per-row height pixels.
     */
    record ResizeOutputBand(double anchorY, double originalRowHeight, int rowCount) implements ClipDragState {}

    /**
     * Reordering a track by dragging its label. {@code originIndex} is where
     * the track sat at press time; {@code dropIndex} is the prospective new
     * insertion point, recomputed from mouse Y on each drag step.
     */
    record MoveTrack(String trackId, int originIndex, int dropIndex) implements ClipDragState {}
}
