package ax.xz.mri.ui.timeline.scrub;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

import java.util.function.DoubleUnaryOperator;

/**
 * The single scrub-strip primitive used everywhere in the studio that
 * "shows a 1D range with optional cursor / window / spans you can drag".
 *
 * <p>One Region. One implementation. Three call sites:
 *
 * <table>
 *   <caption>Mode summary</caption>
 *   <tr><th>Use site</th><th>Reference range</th><th>Window represents</th><th>Click priority</th></tr>
 *   <tr><td>Editor's top time-axis ribbon</td><td>Viewport</td><td>Analysis window</td><td>Marker (cursor)</td></tr>
 *   <tr><td>Editor's bottom overview</td><td>Domain</td><td>Viewport</td><td>Window (pan/zoom)</td></tr>
 *   <tr><td>Trace / heat-map minimap</td><td>Domain</td><td>Analysis window</td><td>Window (pan/zoom)</td></tr>
 *   <tr><td>Geometry pane Z-slice picker</td><td>Z-domain</td><td>Slice height</td><td>Window (pan/zoom)</td></tr>
 * </table>
 *
 * <p>The component is data-only: it doesn't own any state, just exposes
 * properties that callers wire to whichever {@link ax.xz.mri.ui.time.TimeAxis}
 * fields make sense for the context.
 *
 * <p>Rendering is a Canvas (background, spans, ticks) layered with real
 * scene-graph Nodes for the three interactive elements (cursor handle,
 * window body, window edges). Real Nodes give us CSS pseudo-classes
 * ({@code :hover}, {@code :dragging}) and JavaFX's MouseEvent capture model
 * routes drag/release back to the press target without any custom dispatch.
 *
 * <p>Interaction summary (HORIZONTAL; VERTICAL is the rotated equivalent):
 * <ul>
 *   <li>Click on marker (cursor) handle: starts a marker drag.</li>
 *   <li>Click on window-start / window-end edge: starts a window-edge drag.</li>
 *   <li>Click on window body: either drags the window (WINDOW priority) or
 *       scrubs the marker (MARKER priority).</li>
 *   <li>Click on track outside the window: snaps the window centre there
 *       (WINDOW priority) or scrubs the marker (MARKER priority).</li>
 *   <li>Double-click anywhere: invokes {@link #onReset} if set, typically
 *       "fit window to domain".</li>
 *   <li>Scroll wheel: zooms the window around the mouse position via
 *       {@link #onZoom}; ⌘ / Ctrl reverses (we already get this via
 *       JavaFX scroll-event modifiers — caller decides).</li>
 *   <li>ESC during a drag: cancels the drag, restoring pre-drag state.</li>
 * </ul>
 */
public final class ScrubStrip extends Region {
    private static final PseudoClass HOVER     = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass DRAGGING  = PseudoClass.getPseudoClass("dragging");
    private static final double DEFAULT_HEIGHT = 36;
    private static final double EDGE_HIT_PX    = 6;
    private static final double MARKER_HALF    = 7;

    public enum Orientation { HORIZONTAL, VERTICAL }

    /** Click priority — what does a bare click on the strip target? */
    public enum InteractionPriority {
        /** Click scrubs the marker (cursor). Edges of the window stay grabbable. */
        MARKER,
        /** Click pans/zooms the window. Marker is read-only. */
        WINDOW
    }

    public enum Style {
        /** Top-of-editor: tall, ticks shown, marker emphasised, analysis tint behind window. */
        RIBBON,
        /** Bottom-of-editor / above-traces: short, condensed, viewport rectangle emphasised. */
        OVERVIEW
    }

    /** A read-only highlight band — used for RF spans, slice spans, etc. */
    public record Span(double start, double end, Color colour, double opacity) {}

    // ── Data properties (caller binds these) ─────────────────────────────────

    public final ObjectProperty<Orientation> orientation = new SimpleObjectProperty<>(Orientation.HORIZONTAL);
    public final ObjectProperty<InteractionPriority> priority = new SimpleObjectProperty<>(InteractionPriority.WINDOW);
    public final ObjectProperty<Style> style = new SimpleObjectProperty<>(Style.OVERVIEW);

    public final DoubleProperty domainStart = new SimpleDoubleProperty(0);
    public final DoubleProperty domainEnd   = new SimpleDoubleProperty(1);
    public final DoubleProperty windowStart = new SimpleDoubleProperty(0);
    public final DoubleProperty windowEnd   = new SimpleDoubleProperty(1);
    public final DoubleProperty marker      = new SimpleDoubleProperty(0);

    public final BooleanProperty windowVisible    = new SimpleBooleanProperty(true);
    public final BooleanProperty windowEditable   = new SimpleBooleanProperty(true);
    public final BooleanProperty markerVisible    = new SimpleBooleanProperty(true);
    public final BooleanProperty markerEditable   = new SimpleBooleanProperty(false);
    public final BooleanProperty showTicks        = new SimpleBooleanProperty(true);

    public final ObservableList<Span> spans = FXCollections.observableArrayList();

    /** Optional formatter for tick labels — defaults to {@code "%.0f"}. */
    public final ObjectProperty<DoubleUnaryOperator> tickValueTransform = new SimpleObjectProperty<>(t -> t);
    public final ObjectProperty<TickFormatter> tickFormatter = new SimpleObjectProperty<>(DefaultTickFormatter.INSTANCE);

    /** Optional handlers (caller wires these in). */
    public final ObjectProperty<Runnable> onReset = new SimpleObjectProperty<>();
    public final ObjectProperty<ZoomHandler> onZoom = new SimpleObjectProperty<>();

    /** Minimum window span in domain units — prevents collapsing to zero. */
    public final DoubleProperty minWindowSpan = new SimpleDoubleProperty(1);

    @FunctionalInterface
    public interface ZoomHandler { void zoom(double anchor, double factor); }

    /** Pluggable tick formatter — picks unit prefixes per axis range. */
    public interface TickFormatter {
        /** Format a tick value into a label, given the visible span (so the
         *  formatter can pick a single SI unit per axis). */
        String format(double value, double visibleSpan);
        /** Compute a "nice" decade-aligned step that produces about
         *  {@code targetTicks} labels across {@code span}. */
        default double niceStep(double span, double targetTicks) {
            if (span <= 0 || targetTicks <= 0) return 1;
            double raw = span / targetTicks;
            double exp = Math.pow(10, Math.floor(Math.log10(raw)));
            double mantissa = raw / exp;
            double nice =
                mantissa < 1.5 ? 1
              : mantissa < 3.5 ? 2
              : mantissa < 7.5 ? 5
              : 10;
            return nice * exp;
        }
    }

    /** Default formatter: just {@code "%.2f"} of the raw value. */
    public enum DefaultTickFormatter implements TickFormatter {
        INSTANCE;
        @Override public String format(double v, double span) { return String.format("%.2f", v); }
    }

    // ── Visual children ──────────────────────────────────────────────────────

    private final Canvas canvas       = new Canvas();
    private final Rectangle windowRect = new Rectangle();
    private final Region windowStartHandle = new Region();
    private final Region windowEndHandle   = new Region();
    private final Polygon markerHandle = new Polygon();

    // ── Drag state ───────────────────────────────────────────────────────────

    private Drag drag;
    private double dragStartWindowStart;
    private double dragStartWindowEnd;
    private double dragStartMarker;
    private double dragStartValue;

    private enum Drag { MARKER, WINDOW_BODY, WINDOW_START, WINDOW_END }

    public ScrubStrip() {
        getStyleClass().add("scrub-strip");
        setMinHeight(DEFAULT_HEIGHT);
        setPrefHeight(DEFAULT_HEIGHT);
        setMaxHeight(DEFAULT_HEIGHT);
        setSnapToPixel(true);

        canvas.setMouseTransparent(true);
        windowRect.getStyleClass().add("scrub-window");
        windowRect.setArcWidth(4);
        windowRect.setArcHeight(4);

        windowStartHandle.getStyleClass().addAll("scrub-window-edge", "start");
        windowEndHandle  .getStyleClass().addAll("scrub-window-edge", "end");

        markerHandle.getStyleClass().add("scrub-marker");
        markerHandle.setCursor(Cursor.H_RESIZE);

        getChildren().addAll(canvas, windowRect, windowStartHandle, windowEndHandle, markerHandle);

        // A Rectangle clip so handles never bleed past our bounds when their
        // values are out of range — happens transiently during fast drags.
        var clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        installRepaintTriggers();
        installMouseHandlers();

        widthProperty() .addListener((obs, o, n) -> { repaint(); reposition(); });
        heightProperty().addListener((obs, o, n) -> { repaint(); reposition(); });

        // Class-level mode flags drive CSS state.
        style.addListener((obs, o, n) -> {
            getStyleClass().removeAll("scrub-strip-ribbon", "scrub-strip-overview");
            if (n != null) getStyleClass().add(n == Style.RIBBON ? "scrub-strip-ribbon" : "scrub-strip-overview");
        });
        style.set(style.get());

        windowEditable.addListener((obs, o, n) -> reposition());
        windowVisible.addListener((obs, o, n) -> reposition());
        markerVisible.addListener((obs, o, n) -> markerHandle.setVisible(n));
        markerEditable.addListener((obs, o, n) ->
            markerHandle.setCursor(n ? Cursor.H_RESIZE : Cursor.DEFAULT));
        markerHandle.setMouseTransparent(false);
    }

    // ── Layout / pref-size policy ────────────────────────────────────────────

    @Override protected double computePrefWidth(double h)  { return 0; }
    @Override protected double computePrefHeight(double w) { return DEFAULT_HEIGHT; }
    @Override protected double computeMinWidth(double h)   { return 0; }
    @Override protected double computeMinHeight(double w)  { return 24; }

    /**
     * Self-load our stylesheet so the strip's window/edges/marker children get
     * their CSS-driven fills applied no matter where the strip lives in the
     * scene-graph. Without this, surfaces that reuse the strip (trace and
     * heat-map panes, geometry pane) would render the {@link Rectangle}
     * window-body with JavaFX's default fill — solid black — because their
     * scenes don't include the timeline's CSS.
     */
    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/timeline/scrub-strip.css").toExternalForm();
    }

    @Override
    protected void layoutChildren() {
        // Cap at JavaFX's hardware-texture ceiling. Without this, a strip
        // stretched across a fullscreen monitor (e.g. 17246 px × 36 px)
        // crashes the renderer with `Requested texture dimensions
        // (17246x36) exceed maximum texture size (16384)`. The painter
        // doesn't care: it always paints in viewport coords, and the
        // visible portion is always inside the cap.
        double w = Math.min(getWidth(),  16000);
        double h = Math.min(getHeight(), 16000);
        canvas.setWidth(w);
        canvas.setHeight(h);
        repaint();
        reposition();
    }

    // ── Pixel ↔ value ────────────────────────────────────────────────────────

    /** Map a domain value to a pixel position along the strip's axis. */
    public double valueToPx(double v) {
        double ds = domainStart.get();
        double de = domainEnd.get();
        double span = Math.max(1e-12, de - ds);
        double extent = orientation.get() == Orientation.HORIZONTAL ? getWidth() : getHeight();
        double t = (v - ds) / span;
        if (orientation.get() == Orientation.HORIZONTAL) return clamp(t * extent, 0, extent);
        return clamp(extent - t * extent, 0, extent); // vertical: high=top
    }

    /** Map an axis pixel back to a domain value. */
    public double pxToValue(double px) {
        double ds = domainStart.get();
        double de = domainEnd.get();
        double extent = orientation.get() == Orientation.HORIZONTAL ? getWidth() : getHeight();
        if (extent <= 0) return ds;
        double t = orientation.get() == Orientation.HORIZONTAL ? (px / extent) : (1 - px / extent);
        return clamp(ds + t * (de - ds), ds, de);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── Repaint ──────────────────────────────────────────────────────────────

    private void installRepaintTriggers() {
        Runnable repaint = () -> { repaint(); reposition(); };
        for (var p : new javafx.beans.value.ObservableValue<?>[]{
            orientation, priority, style,
            domainStart, domainEnd, windowStart, windowEnd, marker,
            windowVisible, windowEditable, markerVisible, markerEditable, showTicks,
            tickFormatter, tickValueTransform
        }) {
            p.addListener((obs, o, n) -> repaint.run());
        }
        spans.addListener((javafx.collections.ListChangeListener<Span>) c -> repaint.run());
    }

    private void reposition() {
        boolean horiz = orientation.get() == Orientation.HORIZONTAL;
        double w = getWidth();
        double h = getHeight();

        if (windowVisible.get() && domainStart.get() < domainEnd.get()) {
            double ws = valueToPx(windowStart.get());
            double we = valueToPx(windowEnd.get());
            if (horiz) {
                double left = Math.min(ws, we);
                double right = Math.max(ws, we);
                windowRect.setX(left);
                windowRect.setY(0);
                windowRect.setWidth(Math.max(0, right - left));
                windowRect.setHeight(h);
                windowStartHandle.setVisible(windowEditable.get());
                windowEndHandle.setVisible(windowEditable.get());
                windowStartHandle.setCursor(Cursor.H_RESIZE);
                windowEndHandle.setCursor(Cursor.H_RESIZE);
                windowStartHandle.resizeRelocate(left - EDGE_HIT_PX / 2, 0, EDGE_HIT_PX, h);
                windowEndHandle  .resizeRelocate(right - EDGE_HIT_PX / 2, 0, EDGE_HIT_PX, h);
                windowRect.setRotate(0);
            } else {
                double top = Math.min(ws, we);
                double bot = Math.max(ws, we);
                windowRect.setX(0);
                windowRect.setY(top);
                windowRect.setWidth(w);
                windowRect.setHeight(Math.max(0, bot - top));
                windowStartHandle.setVisible(windowEditable.get());
                windowEndHandle.setVisible(windowEditable.get());
                windowStartHandle.setCursor(Cursor.V_RESIZE);
                windowEndHandle.setCursor(Cursor.V_RESIZE);
                // For vertical, "start" is the LOW value (bottom in screen coords).
                windowStartHandle.resizeRelocate(0, bot - EDGE_HIT_PX / 2, w, EDGE_HIT_PX);
                windowEndHandle  .resizeRelocate(0, top - EDGE_HIT_PX / 2, w, EDGE_HIT_PX);
            }
            windowRect.setVisible(true);
            // If WINDOW priority, the body itself is grabbable; otherwise it lets clicks fall through.
            windowRect.setMouseTransparent(priority.get() != InteractionPriority.WINDOW);
        } else {
            windowRect.setVisible(false);
            windowStartHandle.setVisible(false);
            windowEndHandle.setVisible(false);
        }

        markerHandle.setVisible(markerVisible.get());
        if (markerVisible.get()) {
            double mp = valueToPx(marker.get());
            if (horiz) {
                rebuildMarkerHorizontal(h);
                markerHandle.setLayoutX(mp);
                markerHandle.setLayoutY(0);
            } else {
                rebuildMarkerVertical(w);
                markerHandle.setLayoutX(0);
                markerHandle.setLayoutY(mp);
            }
        }
    }

    private void rebuildMarkerHorizontal(double h) {
        markerHandle.getPoints().setAll(
            -MARKER_HALF, 0.0,
             MARKER_HALF, 0.0,
             0.0,         h);
    }

    private void rebuildMarkerVertical(double w) {
        markerHandle.getPoints().setAll(
            0.0, -MARKER_HALF,
            0.0,  MARKER_HALF,
            w,    0.0);
    }

    private void repaint() {
        var g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        g.clearRect(0, 0, w, h);

        // Background
        g.setFill(Color.web("#f4f5f7"));
        g.fillRoundRect(0, 0, w, h, 6, 6);

        if (domainStart.get() >= domainEnd.get()) return;

        // Spans (RF, slice highlights, etc.) — rendered behind the window.
        for (var span : spans) {
            if (span.colour == null) continue;
            if (span.end <= span.start) continue;
            double a = valueToPx(span.start);
            double b = valueToPx(span.end);
            double lo = Math.min(a, b);
            double hi = Math.max(a, b);
            g.setFill(new Color(span.colour.getRed(), span.colour.getGreen(), span.colour.getBlue(),
                                Math.max(0, Math.min(1, span.opacity))));
            if (orientation.get() == Orientation.HORIZONTAL) {
                g.fillRect(lo, 1, Math.max(1, hi - lo), h - 2);
            } else {
                g.fillRect(1, lo, w - 2, Math.max(1, hi - lo));
            }
        }

        if (showTicks.get()) paintTicks(g, w, h);

        // Outer border to read as a contained band.
        g.setStroke(Color.web("#c5cad1"));
        g.setLineWidth(0.5);
        g.strokeRoundRect(0.5, 0.5, w - 1, h - 1, 6, 6);
    }

    private void paintTicks(GraphicsContext g, double w, double h) {
        boolean horiz = orientation.get() == Orientation.HORIZONTAL;
        double ds = domainStart.get();
        double de = domainEnd.get();
        double span = Math.max(1, de - ds);
        double extent = horiz ? w : h;
        TickFormatter fmt = tickFormatter.get();
        if (fmt == null) fmt = DefaultTickFormatter.INSTANCE;
        double targetTicks = Math.max(4, extent / 90);
        double step = fmt.niceStep(span, targetTicks);
        double firstTick = Math.ceil(ds / step) * step;

        g.setStroke(Color.web("#c5cad1"));
        g.setFill(Color.web("#5c6571"));
        g.setFont(javafx.scene.text.Font.font(10));
        g.setLineWidth(0.5);
        DoubleUnaryOperator transform = tickValueTransform.get();
        if (transform == null) transform = t -> t;
        for (double t = firstTick; t <= de + 1e-9; t += step) {
            double px = valueToPx(t);
            String label = fmt.format(transform.applyAsDouble(t), span);
            if (horiz) {
                g.strokeLine(px, h - 7, px, h);
                g.fillText(label, px + 3, h - 9);
            } else {
                g.strokeLine(0, px, 7, px);
                g.fillText(label, 9, px - 1);
            }
        }
    }

    // ── Mouse handlers ───────────────────────────────────────────────────────

    private void installMouseHandlers() {
        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPress);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDrag);
        addEventFilter(MouseEvent.MOUSE_RELEASED, this::onRelease);
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);

        // Hover/cursor feedback on the strip body so the user gets a hint.
        addEventFilter(MouseEvent.MOUSE_MOVED, e -> setCursor(cursorFor(e.getX(), e.getY())));
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> setCursor(Cursor.DEFAULT));
    }

    private void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (e.getClickCount() >= 2 && onReset.get() != null) {
            onReset.get().run();
            e.consume();
            return;
        }
        Drag candidate = pickDrag(e.getX(), e.getY());
        if (candidate == null) return;

        dragStartWindowStart = windowStart.get();
        dragStartWindowEnd   = windowEnd.get();
        dragStartMarker      = marker.get();
        dragStartValue       = pxToValue(orientation.get() == Orientation.HORIZONTAL ? e.getX() : e.getY());

        // Apply the on-press action so a bare click (no subsequent drag)
        // still has visible effect — otherwise click-to-scrub-marker fails
        // and click-outside-window doesn't snap.
        switch (candidate) {
            case MARKER -> marker.set(clampToDomain(dragStartValue));
            case WINDOW_BODY -> {
                if (priority.get() == InteractionPriority.WINDOW && !insideWindowPx(e.getX(), e.getY())) {
                    double span = dragStartWindowEnd - dragStartWindowStart;
                    double centre = dragStartValue;
                    setWindowClamped(centre - span / 2, centre + span / 2);
                    dragStartWindowStart = windowStart.get();
                    dragStartWindowEnd   = windowEnd.get();
                }
            }
            default -> { /* edge handles only act on drag */ }
        }

        drag = candidate;
        pseudoClassStateChanged(DRAGGING, true);
        e.consume();
    }

    private void onDrag(MouseEvent e) {
        if (drag == null) return;
        boolean horiz = orientation.get() == Orientation.HORIZONTAL;
        double v = pxToValue(horiz ? e.getX() : e.getY());
        switch (drag) {
            case MARKER -> marker.set(clampToDomain(v));
            case WINDOW_START -> setWindowClamped(v, dragStartWindowEnd);
            case WINDOW_END   -> setWindowClamped(dragStartWindowStart, v);
            case WINDOW_BODY -> {
                double delta = v - dragStartValue;
                setWindowClamped(dragStartWindowStart + delta, dragStartWindowEnd + delta);
            }
        }
        e.consume();
    }

    private void onRelease(MouseEvent e) {
        drag = null;
        pseudoClassStateChanged(DRAGGING, false);
        setCursor(cursorFor(e.getX(), e.getY()));
    }

    private void onScroll(ScrollEvent e) {
        if (onZoom.get() == null) return;
        boolean horiz = orientation.get() == Orientation.HORIZONTAL;
        double v = pxToValue(horiz ? e.getX() : e.getY());
        double factor = e.getDeltaY() > 0 ? 0.85 : 1.18;
        onZoom.get().zoom(v, factor);
        e.consume();
    }

    // ── Hit-testing & drag picking ───────────────────────────────────────────

    private Drag pickDrag(double x, double y) {
        // Position-based hit-testing — works for both real user clicks (where
        // JavaFX picks the deepest hit node) and synthetic events (where the
        // event target may be the strip itself rather than a child handle).
        if (markerEditable.get() && markerVisible.get()
            && markerHandle.getBoundsInParent().contains(x, y)) {
            return Drag.MARKER;
        }
        if (windowVisible.get() && windowEditable.get()) {
            if (windowStartHandle.getBoundsInParent().contains(x, y)) return Drag.WINDOW_START;
            if (windowEndHandle  .getBoundsInParent().contains(x, y)) return Drag.WINDOW_END;
        }
        if (priority.get() == InteractionPriority.WINDOW) {
            return Drag.WINDOW_BODY;
        }
        // MARKER priority and click missed handles → scrub marker.
        if (markerEditable.get()) return Drag.MARKER;
        return null;
    }

    private boolean insideWindowPx(double x, double y) {
        boolean horiz = orientation.get() == Orientation.HORIZONTAL;
        double a = valueToPx(windowStart.get());
        double b = valueToPx(windowEnd.get());
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return horiz ? (x >= lo && x <= hi) : (y >= lo && y <= hi);
    }

    private Cursor cursorFor(double x, double y) {
        if (markerEditable.get() && markerHandle.getBoundsInParent().contains(x, y)) return Cursor.H_RESIZE;
        if (!windowEditable.get() || !windowVisible.get()) return Cursor.DEFAULT;
        if (windowStartHandle.getBoundsInParent().contains(x, y)
         || windowEndHandle.getBoundsInParent().contains(x, y)) {
            return orientation.get() == Orientation.HORIZONTAL ? Cursor.H_RESIZE : Cursor.V_RESIZE;
        }
        if (priority.get() == InteractionPriority.WINDOW && insideWindowPx(x, y)) return Cursor.OPEN_HAND;
        return Cursor.DEFAULT;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private double clampToDomain(double v) {
        return clamp(v, domainStart.get(), domainEnd.get());
    }

    private void setWindowClamped(double s, double e) {
        double ds = domainStart.get();
        double de = domainEnd.get();
        double minSpan = Math.max(0, minWindowSpan.get());
        double span = Math.max(minSpan, e - s);
        double cs = clamp(s, ds, de - span);
        double ce = cs + span;
        if (ce > de) {
            ce = de;
            cs = Math.max(ds, ce - span);
        }
        windowStart.set(cs);
        windowEnd.set(ce);
    }
}
