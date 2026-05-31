package ax.xz.mri.ui.tutorial;

import module javafx.controls;

import javafx.scene.effect.DropShadow;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * Tutorial overlay layer. Sits on top of the entire shell frame (menu bar
 * included) as the last child of the shell's root {@code StackPane}, so its
 * spotlight can highlight any control.
 *
 * <p>Built entirely from scene-graph nodes — no {@code Canvas}, no pixel
 * math:
 * <ul>
 *   <li>a {@linkplain Shape#subtract dim shape with a real hole} cut around
 *       the target, so the control shows through at full brightness;</li>
 *   <li>a soft glowing ring ({@link Rectangle} + {@link DropShadow}) around
 *       the hole, gently pulsed to draw the eye;</li>
 *   <li>a bubble ({@link VBox}) with the step label, title and one-line body,
 *       placed on whichever side of the target keeps it on-screen.</li>
 * </ul>
 *
 * <p>{@code pickOnBounds = false} plus mouse-transparent decoration nodes let
 * every click flow through to the UI beneath; only the bubble's buttons are
 * interactive. The decoration tracks the target via an
 * {@link InvalidationListener} on its bounds / transform and the scene size,
 * so it follows resizes and layout changes with no polling.
 */
public final class TutorialOverlay extends Pane {

    private static final double HOLE_PADDING = 6;
    private static final double HOLE_RADIUS = 8;
    private static final double BUBBLE_WIDTH = 300;
    private static final double BUBBLE_GAP = 18;
    private static final Color DIM = Color.rgb(10, 14, 22, 0.55);
    private static final Color ACCENT = Color.web("#4c8dff");

    private final Rectangle ring = new Rectangle();
    private final VBox bubble = new VBox(5);
    private final Label stepLabel = new Label();
    private final Label titleLabel = new Label();
    private final Label bodyLabel = new Label();

    private Shape dim;                 // replaced whenever the hole moves
    private Node target;
    private InvalidationListener tracker;
    private Runnable onClose;

    private final Timeline pulse;

    public TutorialOverlay() {
        getStyleClass().add("tutorial-overlay");
        setPickOnBounds(false);        // clicks fall through empty regions
        setVisible(false);

        // Glow ring around the spotlight hole.
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(ACCENT);
        ring.setStrokeWidth(2.0);
        ring.setArcWidth(HOLE_RADIUS * 2);
        ring.setArcHeight(HOLE_RADIUS * 2);
        ring.setMouseTransparent(true);
        var glow = new DropShadow();
        glow.setColor(ACCENT);
        glow.setRadius(14);
        glow.setSpread(0.25);
        ring.setEffect(glow);

        // Bubble — the only interactive part of the overlay.
        stepLabel.setStyle("-fx-text-fill: " + toHex(ACCENT) + "; -fx-font-size: 10.5; -fx-font-weight: bold;");
        titleLabel.setStyle("-fx-text-fill: #1a2530; -fx-font-size: 13.5; -fx-font-weight: bold;");
        titleLabel.setWrapText(true);
        bodyLabel.setStyle("-fx-text-fill: #46505c; -fx-font-size: 12;");
        bodyLabel.setWrapText(true);
        var skip = new Button("Skip tutorial");
        skip.setFocusTraversable(false);
        skip.setStyle("-fx-background-color: transparent; -fx-text-fill: #8a94a0; -fx-font-size: 11; -fx-padding: 2 0 0 0;");
        skip.setOnAction(e -> { if (onClose != null) onClose.run(); else hide(); });
        var skipRow = new HBox(skip);
        skipRow.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().setAll(stepLabel, titleLabel, bodyLabel, skipRow);
        bubble.setMaxWidth(BUBBLE_WIDTH);
        bubble.setPrefWidth(BUBBLE_WIDTH);
        bubble.setManaged(false);
        bubble.setPadding(new Insets(14, 16, 12, 16));
        bubble.setStyle(
            "-fx-background-color: white;"
          + "-fx-background-radius: 12;"
          + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.28), 22, 0.2, 0, 6);");

        getChildren().addAll(ring, bubble);

        pulse = new Timeline(
            new KeyFrame(Duration.ZERO,            new KeyValue(ring.opacityProperty(), 0.55)),
            new KeyFrame(Duration.seconds(0.85),   new KeyValue(ring.opacityProperty(), 1.0)),
            new KeyFrame(Duration.seconds(1.70),   new KeyValue(ring.opacityProperty(), 0.55)));
        pulse.setCycleCount(Animation.INDEFINITE);

        // Re-place decoration whenever the overlay is resized.
        widthProperty().addListener((o, a, b) -> relayout());
        heightProperty().addListener((o, a, b) -> relayout());
    }

    /**
     * Show the spotlight on {@code target}. {@code stepText} is a short
     * progress marker (e.g. "Step 1 of 2"); {@code onClose} runs when the
     * user clicks Skip.
     */
    public void showStep(Node target, String stepText, String title, String body, Runnable onClose) {
        this.onClose = onClose;
        detachTracker();
        this.target = target;
        stepLabel.setText(stepText == null ? "" : stepText);
        stepLabel.setVisible(stepText != null && !stepText.isBlank());
        stepLabel.setManaged(stepLabel.isVisible());
        titleLabel.setText(title == null ? "" : title);
        bodyLabel.setText(body == null ? "" : body);

        if (target != null) {
            tracker = obs -> relayout();
            target.boundsInLocalProperty().addListener(tracker);
            target.localToSceneTransformProperty().addListener(tracker);
            var scene = target.getScene();
            if (scene != null) {
                scene.widthProperty().addListener(tracker);
                scene.heightProperty().addListener(tracker);
            }
        }

        setVisible(true);
        toFront();
        ring.setVisible(true);
        bubble.setVisible(true);
        pulse.playFromStart();
        relayout();
    }

    /** Hide the overlay and stop the pulse. */
    public void hide() {
        pulse.stop();
        detachTracker();
        target = null;
        if (dim != null) { getChildren().remove(dim); dim = null; }
        ring.setVisible(false);
        bubble.setVisible(false);
        setVisible(false);
    }

    /* ── Layout ─────────────────────────────────────────────────────────── */

    private void relayout() {
        if (target == null || target.getScene() == null || getScene() == null) return;
        var t = sceneToLocal(target.localToScene(target.getBoundsInLocal()));
        if (t == null || t.getWidth() <= 0 || t.getHeight() <= 0) return;

        double hx = t.getMinX() - HOLE_PADDING, hy = t.getMinY() - HOLE_PADDING;
        double hw = t.getWidth() + 2 * HOLE_PADDING, hh = t.getHeight() + 2 * HOLE_PADDING;

        // Dim everything, subtract the hole so the target shows through.
        var full = new Rectangle(0, 0, getWidth(), getHeight());
        var hole = new Rectangle(hx, hy, hw, hh);
        hole.setArcWidth(HOLE_RADIUS * 2);
        hole.setArcHeight(HOLE_RADIUS * 2);
        var newDim = Shape.subtract(full, hole);
        newDim.setFill(DIM);
        newDim.setMouseTransparent(true);
        if (dim != null) getChildren().remove(dim);
        dim = newDim;
        getChildren().add(0, dim);     // behind ring + bubble

        ring.setX(hx); ring.setY(hy);
        ring.setWidth(hw); ring.setHeight(hh);

        // Place the bubble on whichever side keeps it fully on-screen.
        bubble.applyCss();
        bubble.autosize();
        double bw = bubble.getWidth() > 0 ? bubble.getWidth() : BUBBLE_WIDTH;
        double bh = bubble.getHeight() > 0 ? bubble.getHeight() : bubble.prefHeight(bw);
        double ww = getWidth(), wh = getHeight();
        double bx, by;
        if (hy + hh + BUBBLE_GAP + bh <= wh) {          // below (preferred — never covers neighbours of a top-row target)
            bx = clamp(hx, 8, ww - bw - 8);
            by = hy + hh + BUBBLE_GAP;
        } else if (hx + hw + BUBBLE_GAP + bw <= ww) {   // right
            bx = hx + hw + BUBBLE_GAP;
            by = clamp(hy, 8, wh - bh - 8);
        } else if (hy - BUBBLE_GAP - bh >= 0) {         // above
            bx = clamp(hx, 8, ww - bw - 8);
            by = hy - BUBBLE_GAP - bh;
        } else {                                         // left
            bx = Math.max(8, hx - BUBBLE_GAP - bw);
            by = clamp(hy, 8, wh - bh - 8);
        }
        bubble.resizeRelocate(bx, by, bw, bh);
    }

    private void detachTracker() {
        if (target != null && tracker != null) {
            target.boundsInLocalProperty().removeListener(tracker);
            target.localToSceneTransformProperty().removeListener(tracker);
            var scene = target.getScene();
            if (scene != null) {
                scene.widthProperty().removeListener(tracker);
                scene.heightProperty().removeListener(tracker);
            }
        }
        tracker = null;
    }

    private static double clamp(double v, double lo, double hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue() * 255));
    }
}
