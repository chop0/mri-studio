package ax.xz.mri.ui.substance;

import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.canvas.Camera3D;
import ax.xz.mri.ui.canvas.OrbitView3D;
import ax.xz.mri.ui.canvas.VectorFieldArrowRenderer;
import ax.xz.mri.util.MathUtil;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Direct-manipulation 3-D viewport for an NV-centre ensemble.
 *
 * <p>Built on the shared {@link OrbitView3D} orbit-camera + orthographic
 * projection (so it matches every other 3-D surface in the studio), with three
 * additions on top:
 *
 * <ul>
 *   <li>The set of NV centres is an {@link ObservableList} the editor owns —
 *       drag, add, and delete operations mutate the list directly and the
 *       editor's mutation pipeline replays the change into the document.</li>
 *   <li>A {@link NvConstraint} snaps Add-tool clicks and Move drags to a plane
 *       or line. {@link NvConstraint.None} leaves drags free in the camera-aligned
 *       plane at the centre's current depth.</li>
 *   <li>An optional {@link EigenfieldScript} renders a translucent
 *       {@link VectorFieldArrowRenderer vector-arrow field} in the background so
 *       the user sees the field the NVs would sense. Its density adapts to zoom
 *       — zoom in for more field detail.</li>
 * </ul>
 *
 * <p>Tool dispatch is driven by an {@link NvEditorTool} property the editor's
 * toolbar binds to. A hover-position {@code Vec3} is exposed for an ANSYS-style
 * status hint at the bottom of the editor pane.
 */
public final class NvScatter3DCanvas extends OrbitView3D {

    /** Half-extent of the viewport box in metres. Default 4 µm matches the NV starter FOV. */
    public static final double DEFAULT_HALF_EXTENT_M = 4e-6;
    /** Hit-test radius around an NV centre projection, in screen pixels. */
    private static final double HIT_RADIUS_PX = 9;

    private final ObservableList<NvCentre> centres = FXCollections.observableArrayList();

    private final ObjectProperty<EigenfieldScript> overlayScript = new SimpleObjectProperty<>();
    private final IntegerProperty overlaySamples = new SimpleIntegerProperty(7);
    private final ObjectProperty<NvConstraint> constraint = new SimpleObjectProperty<>(new NvConstraint.None());
    private final ObjectProperty<NvEditorTool> activeTool = new SimpleObjectProperty<>(NvEditorTool.SELECT);
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);

    /** Updated continuously as the cursor moves — the editor pane reads it for status hints. */
    private final ObjectProperty<Vec3> hoverWorldPosition = new SimpleObjectProperty<>(Vec3.ZERO);

    private final VectorFieldArrowRenderer overlayRenderer = new VectorFieldArrowRenderer().opacity(0.85);
    private final VectorFieldArrowRenderer.Field overlayField = (x, y, z) -> {
        var s = overlayScript.get();
        if (s == null) return Vec3.ZERO;
        var v = s.evaluate(x, y, z);
        return v == null ? Vec3.ZERO : v;
    };

    /** Hook the editor passes in to commit centre mutations into the document. */
    private Consumer<List<NvCentre>> onCentresMutated;
    /** Hook the editor passes in for right-click context menus. */
    private ContextMenuRequest contextMenuRequest;

    private double dragX, dragY;
    private boolean orbiting;
    private int draggingIndex = -1;

    public NvScatter3DCanvas() {
        super(DEFAULT_HALF_EXTENT_M);
        overlayRenderer.baseSamplesPerAxis(overlaySamples.get());

        centres.addListener((javafx.collections.ListChangeListener<NvCentre>) c -> requestRedraw());
        installRedrawOn(overlayScript, overlaySamples, constraint, selectedIndex, activeTool);
        overlayScript.addListener((o, a, b) -> overlayRenderer.invalidate());
        overlaySamples.addListener((o, a, b) -> overlayRenderer.baseSamplesPerAxis(b.intValue()));

        installScrollZoom();
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setOnContextMenuRequested(e -> {
            if (contextMenuRequest == null) return;
            int hit = hitTest(e.getX(), e.getY());
            contextMenuRequest.fire(hit, camera().worldAtScreen(e.getX(), e.getY(), 0), e.getScreenX(), e.getScreenY());
        });
    }

    /* ── Public surface ──────────────────────────────────────────────────── */

    public ObservableList<NvCentre> centres() { return centres; }
    public ObjectProperty<EigenfieldScript> overlayScriptProperty() { return overlayScript; }
    public IntegerProperty overlaySamplesProperty() { return overlaySamples; }
    public ObjectProperty<NvConstraint> constraintProperty() { return constraint; }
    public ObjectProperty<NvEditorTool> activeToolProperty() { return activeTool; }
    public IntegerProperty selectedIndexProperty() { return selectedIndex; }
    public javafx.beans.value.ObservableObjectValue<Vec3> hoverWorldPositionProperty() { return hoverWorldPosition; }

    /** Called whenever the centre list mutates (drag end, add, delete, stamp). */
    public void setOnCentresMutated(Consumer<List<NvCentre>> handler) { this.onCentresMutated = handler; }

    /** Called when the user right-clicks. {@code hitIndex} is -1 if empty canvas. */
    public void setContextMenuRequest(ContextMenuRequest handler) { this.contextMenuRequest = handler; }

    public interface ContextMenuRequest {
        void fire(int hitIndex, Vec3 worldAtCursor, double screenX, double screenY);
    }

    /* ── Mouse handling ──────────────────────────────────────────────────── */

    private void onMousePressed(MouseEvent e) {
        canvas.requestFocus();
        dragX = e.getX();
        dragY = e.getY();
        orbiting = false;
        draggingIndex = -1;
        if (e.getButton() != MouseButton.PRIMARY) return;

        var tool = activeTool.get();
        int hit = hitTest(e.getX(), e.getY());

        switch (tool) {
            case SELECT -> {
                selectedIndex.set(hit);
                if (hit >= 0) draggingIndex = hit;
                else orbiting = true;
            }
            case ADD -> {
                if (hit < 0) {
                    Vec3 world = constraint.get().project(camera().worldAtScreen(e.getX(), e.getY(), 0));
                    centres.add(new NvCentre(world.x(), world.y(), world.z(), NvAxis.AXIS_PLUS_Z));
                    selectedIndex.set(centres.size() - 1);
                    notifyMutated();
                }
            }
            case DELETE -> {
                if (hit >= 0) {
                    centres.remove(hit);
                    selectedIndex.set(-1);
                    notifyMutated();
                }
            }
            case ORBIT -> orbiting = true;
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        double dx = e.getX() - dragX;
        double dy = e.getY() - dragY;
        dragX = e.getX();
        dragY = e.getY();

        if (orbiting) {
            orbitBy(dx, dy);
        } else if (draggingIndex >= 0 && draggingIndex < centres.size()) {
            // Translate the centre by the world-delta of the screen drag, then
            // snap to the active constraint surface.
            var c = centres.get(draggingIndex);
            Vec3 delta = camera().screenDeltaToWorld(dx, dy);
            Vec3 moved = new Vec3(c.xMetres() + delta.x(), c.yMetres() + delta.y(), c.zMetres() + delta.z());
            moved = constraint.get().project(moved);
            centres.set(draggingIndex, new NvCentre(moved.x(), moved.y(), moved.z(), c.axis()));
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (draggingIndex >= 0) notifyMutated();
        draggingIndex = -1;
        orbiting = false;
    }

    private void onMouseMoved(MouseEvent e) {
        hoverWorldPosition.set(camera().worldAtScreen(e.getX(), e.getY(), 0));
    }

    /* ── Hit-testing ─────────────────────────────────────────────────────── */

    /** Returns the index of the closest NV centre within {@link #HIT_RADIUS_PX}, or -1. */
    public int hitTest(double screenX, double screenY) {
        var cam = camera();
        int best = -1;
        double bestDist = HIT_RADIUS_PX;
        for (int i = 0; i < centres.size(); i++) {
            var c = centres.get(i);
            var p = cam.projectMetres(c.xMetres(), c.yMetres(), c.zMetres());
            double d = Math.hypot(p[0] - screenX, p[1] - screenY);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /* ── Rendering ───────────────────────────────────────────────────────── */

    @Override
    protected void drawScene(GraphicsContext g, Camera3D cam) {
        drawBoundingCube(g, cam);
        drawAxes(g, cam);
        drawConstraint(g, cam);
        if (overlayScript.get() != null) overlayRenderer.draw(g, cam, overlayField);
        drawCentres(g, cam);
        drawLegend(g, cam);
    }

    private void drawCentres(GraphicsContext g, Camera3D cam) {
        record P(int index, double[] s, double depth) {}
        var ordered = new ArrayList<P>(centres.size());
        for (int i = 0; i < centres.size(); i++) {
            var c = centres.get(i);
            var p = cam.projectMetres(c.xMetres(), c.yMetres(), c.zMetres());
            ordered.add(new P(i, p, p[2]));
        }
        ordered.sort((a, b) -> Double.compare(a.depth(), b.depth()));

        int sel = selectedIndex.get();
        for (var p : ordered) {
            double r = 4 + 1.5 * ((p.depth() + 1) * 0.5);
            boolean selected = p.index() == sel;
            g.setFill(selected ? Color.web("#fde725") : Color.web("#5ec962"));
            g.fillOval(p.s()[0] - r, p.s()[1] - r, 2 * r, 2 * r);
            g.setStroke(Color.color(0, 0, 0, 0.6));
            g.setLineWidth(1.0);
            g.strokeOval(p.s()[0] - r, p.s()[1] - r, 2 * r, 2 * r);
            if (selected) {
                g.setStroke(Color.web("#fde725"));
                g.setLineWidth(1.4);
                g.strokeOval(p.s()[0] - r - 4, p.s()[1] - r - 4, 2 * r + 8, 2 * r + 8);
            }
        }
    }

    private void drawConstraint(GraphicsContext g, Camera3D cam) {
        var con = constraint.get();
        if (con instanceof NvConstraint.None) return;
        double half = cam.halfExtentM();
        double[][] quad = switch (con) {
            case NvConstraint.PlaneZ p -> {
                double z = MathUtil.clamp(p.z0() / half, -1, 1);
                yield new double[][] { {-1, -1, z}, {+1, -1, z}, {+1, +1, z}, {-1, +1, z} };
            }
            case NvConstraint.PlaneY p -> {
                double y = MathUtil.clamp(p.y0() / half, -1, 1);
                yield new double[][] { {-1, y, -1}, {+1, y, -1}, {+1, y, +1}, {-1, y, +1} };
            }
            case NvConstraint.PlaneX p -> {
                double x = MathUtil.clamp(p.x0() / half, -1, 1);
                yield new double[][] { {x, -1, -1}, {x, +1, -1}, {x, +1, +1}, {x, -1, +1} };
            }
            case NvConstraint.LineX l -> {
                drawLine(g, cam, new double[]{-1, l.y0()/half, l.z0()/half}, new double[]{+1, l.y0()/half, l.z0()/half});
                yield null;
            }
            case NvConstraint.LineY l -> {
                drawLine(g, cam, new double[]{l.x0()/half, -1, l.z0()/half}, new double[]{l.x0()/half, +1, l.z0()/half});
                yield null;
            }
            case NvConstraint.LineZ l -> {
                drawLine(g, cam, new double[]{l.x0()/half, l.y0()/half, -1}, new double[]{l.x0()/half, l.y0()/half, +1});
                yield null;
            }
            case NvConstraint.None n -> null;
        };
        if (quad == null) return;
        var p0 = cam.projectNorm(quad[0][0], quad[0][1], quad[0][2]);
        var p1 = cam.projectNorm(quad[1][0], quad[1][1], quad[1][2]);
        var p2 = cam.projectNorm(quad[2][0], quad[2][1], quad[2][2]);
        var p3 = cam.projectNorm(quad[3][0], quad[3][1], quad[3][2]);
        double[] xs = {p0[0], p1[0], p2[0], p3[0]};
        double[] ys = {p0[1], p1[1], p2[1], p3[1]};
        g.setFill(Color.color(0.4, 0.6, 0.9, 0.13));
        g.fillPolygon(xs, ys, 4);
        g.setStroke(Color.color(0.4, 0.6, 0.9, 0.45));
        g.setLineWidth(1.0);
        g.strokePolygon(xs, ys, 4);
    }

    private void drawLine(GraphicsContext g, Camera3D cam, double[] a, double[] b) {
        var pa = cam.projectNorm(a[0], a[1], a[2]);
        var pb = cam.projectNorm(b[0], b[1], b[2]);
        g.setStroke(Color.color(0.4, 0.6, 0.9, 0.65));
        g.setLineWidth(1.5);
        g.strokeLine(pa[0], pa[1], pb[0], pb[1]);
    }

    private void drawLegend(GraphicsContext g, Camera3D cam) {
        g.setGlobalAlpha(1);
        g.setFill(Color.color(1, 1, 1, 0.55));
        g.setFont(Font.font("System", 10));
        g.fillText(String.format("%d centres   half = %.3g µm   constraint = %s",
            centres.size(), cam.halfExtentM() * 1e6, constraint.get().displayName()),
            10, canvas.getHeight() - 10);
    }

    private void notifyMutated() {
        if (onCentresMutated != null) onCentresMutated.accept(new ArrayList<>(centres));
    }
}
