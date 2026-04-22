package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.EditorTrack;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.ViewportViewModel;
import ax.xz.mri.ui.workbench.pane.WaveformCache;
import javafx.animation.AnimationTimer;
import javafx.scene.input.MouseEvent;

/**
 * Multi-track DAW canvas for editing signal clips.
 *
 * <p>Thin shell that wires together three specialised collaborators:
 * <ul>
 *   <li>{@link TimelineGeometry} — immutable view math, rebuilt each frame.</li>
 *   <li>{@link TimelineRenderer} — stateless paint pipeline.</li>
 *   <li>{@link TimelineInteraction} — mouse-driven state machine that mutates
 *       the session and owns the current drag/snap state.</li>
 * </ul>
 *
 * <p>The canvas itself owns an {@link AnimationTimer} that redraws when the
 * {@code dirty} flag is set, plus a {@link WaveformCache} for pre-sampled clip
 * waveforms. No rendering or hit-testing code lives here.
 */
public final class TimelineCanvas extends ResizableCanvas {

    /** Width of the left-hand label column (px). */
    public static final double LABEL_WIDTH = 78;
    /** Right edge padding (px). */
    public static final double RIGHT_PAD = 8;
    /** Bottom padding reserved for the time axis (px). */
    public static final double BOTTOM_PAD = 18;
    /** Height of a single collapsed-lane bar (px). */
    public static final double COLLAPSED_HEIGHT = 16;

    private final SequenceEditSession session;
    private final WaveformCache waveforms = new WaveformCache();
    private final TimelineInteraction interaction;
    private final AnimationTimer timer;

    private ViewportViewModel viewport;
    private boolean dirty = true;

    public TimelineCanvas(SequenceEditSession session) {
        this.session = session;
        getStyleClass().add("timeline-canvas");

        this.interaction = new TimelineInteraction(
            session,
            this::geometry,
            this::markDirty,
            this::setCursor
        );

        // Redraw triggers — session properties that affect the rendered scene
        setOnResized(this::markDirty);
        session.revision.addListener((obs, o, n) -> { waveforms.clear(); markDirty(); });
        session.viewStart.addListener((obs, o, n) -> markDirty());
        session.viewEnd.addListener((obs, o, n) -> markDirty());
        session.selectedClipIds.addListener((javafx.collections.SetChangeListener<String>) c -> markDirty());
        session.tracks.addListener((javafx.collections.ListChangeListener<EditorTrack>) c -> markDirty());
        session.collapsedChannels.addListener(
            (javafx.collections.SetChangeListener<ax.xz.mri.model.sequence.SequenceChannel>) c -> markDirty());

        // Mouse wiring — one line per event, all logic lives in interaction.
        setOnMousePressed(interaction::onPress);
        setOnMouseDragged(interaction::onDrag);
        setOnMouseReleased(interaction::onRelease);
        setOnMouseMoved(interaction::onMouseMoved);
        setOnScroll(interaction::onScroll);
        setOnContextMenuRequested(e -> interaction.onContextMenu(this, e));
        // Dismiss any open context menu on primary-down anywhere on the canvas.
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.isPrimaryButtonDown()) interaction.dismissContextMenu();
        });

        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (dirty) { dirty = false; paint(); }
            }
        };
        timer.start();
    }

    /** Wire the global viewport (for cursor line display). */
    public void setViewport(ViewportViewModel vp) {
        this.viewport = vp;
        vp.tC.addListener((obs, o, n) -> markDirty());
    }

    /** Change the active clip-creation tool (passed in from the tool palette). */
    public void setActiveCreationKind(ClipKind kind) {
        interaction.setActiveCreationKind(kind);
    }

    public void dispose() { timer.stop(); }

    private void markDirty() { dirty = true; }

    private TimelineGeometry geometry() {
        return new TimelineGeometry(
            session.tracks, session.collapsedChannels,
            session.viewStart.get(), session.viewEnd.get(),
            getWidth(), getHeight(),
            LABEL_WIDTH, RIGHT_PAD, BOTTOM_PAD, COLLAPSED_HEIGHT
        );
    }

    private void paint() {
        var geom = geometry();
        Double cursor = viewport != null ? viewport.tC.get() : null;
        TimelineRenderer.render(
            getGraphicsContext2D(),
            geom, session, waveforms,
            interaction.drag(),
            interaction.snapGuideTime(),
            cursor
        );
    }
}
