package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.canvas.ColourUtil;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.Geometry3DCanvas;
import ax.xz.mri.ui.viewmodel.GeometryShadingSnapshot;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringSupport;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringViewModel;
import ax.xz.mri.ui.viewmodel.SlicePlane;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.beans.InvalidationListener;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Geometry / cross-section pane (Part 8 v1).
 *
 * <p>Two stacked surfaces:
 *
 * <ol>
 *   <li>{@link Geometry3DCanvas} on top — CAD-style 3-D viewport for the FOV,
 *       substances, optional eigenfield overlay, and the active slicing
 *       plane (translucent quad with a draggable origin handle).</li>
 *   <li>A 2-D heatmap below — renders the cells of {@link GeometryShadingSnapshot}
 *       in the plane's own (u, v) basis. The horizontal axis is {@code u},
 *       the vertical axis is {@code v}; both in metres in the lab frame.</li>
 * </ol>
 *
 * <p>The toolbar carries canonical camera-view buttons (+X / +Y / +Z / ISO)
 * and plane-normal preset buttons (⊥X / ⊥Y / ⊥Z). Snapping (NV centres + axes)
 * lives in the 3-D canvas itself.
 */
public class GeometryPane extends WorkbenchPane {

    private static final double PAD_LEFT = 36;
    private static final double PAD_TOP = 8;
    private static final double PAD_BOTTOM = 22;
    private static final double PAD_RIGHT = 10;

    private final Geometry3DCanvas editor = new Geometry3DCanvas();
    private final Canvas heatmap = new Canvas(600, 240);

    public GeometryPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Geometry");

        var session = paneContext.session();

        // Wire the 3-D editor.
        editor.simulationProperty().bind(session.document.simulation);
        editor.planeProperty().bindBidirectional(session.geometry.slicePlane);

        // Toolbar.
        var labels = new CheckBox("Labels");
        labels.selectedProperty().bindBidirectional(session.geometry.showLabels);
        var colourMenu = MagnetisationColouringControls.newMenuButton(session.colouring);
        var toolbar = buildToolbar(labels, colourMenu);
        setToolNodes(toolbar);

        // Vertical split: 3-D editor on top, heatmap below.
        heatmap.widthProperty().addListener((obs, o, n) -> redrawHeatmap());
        heatmap.heightProperty().addListener((obs, o, n) -> redrawHeatmap());
        var heatHolder = new javafx.scene.layout.StackPane(heatmap);
        heatmap.widthProperty().bind(heatHolder.widthProperty());
        heatmap.heightProperty().bind(heatHolder.heightProperty());

        var split = new SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().setAll(editor, heatHolder);
        split.setDividerPositions(0.55);

        setPaneContent(split);

        // Redraw triggers.
        InvalidationListener redraw = obs -> redrawHeatmap();
        session.geometry.shadingSnapshot.addListener(redraw);
        session.geometry.shadingComputing.addListener(redraw);
        session.geometry.statusMessage.addListener(redraw);
        session.geometry.slicePlane.addListener(redraw);
        session.colouring.hueSource.addListener(redraw);
        session.colouring.brightnessSource.addListener(redraw);
        session.points.entries.addListener((InvalidationListener) obs -> redrawHeatmap());
        session.selection.selectedIds.addListener(redraw);
        session.timeAxis.cursor.time.addListener(redraw);
        session.geometry.showLabels.addListener(redraw);
        session.document.simulation.addListener(redraw);
        session.document.currentPulse.addListener(redraw);

        heatmap.setOnMouseMoved(this::onHeatmapHover);
        heatmap.setOnMouseExited(e -> setPaneStatus(""));
        heatmap.setOnMousePressed(this::onHeatmapClick);
        heatmap.setOnContextMenuRequested(e -> {
            var menu = buildHeatmapMenu(e.getX(), e.getY());
            showCanvasContextMenu(menu, e.getScreenX(), e.getScreenY());
        });

        redrawHeatmap();
    }

    /* ── Toolbar ─────────────────────────────────────────────────────── */

    private HBox buildToolbar(CheckBox labels, javafx.scene.Node colourMenu) {
        var session = paneContext.session();
        // Camera-view group.
        var iso = btn("ISO", e -> editor.setIsoView(), "Iso 3-D camera");
        var px = btn("+X", e -> editor.setPlusXView(), "Camera looking down +X");
        var py = btn("+Y", e -> editor.setPlusYView(), "Camera looking down +Y");
        var pz = btn("+Z", e -> editor.setPlusZView(), "Camera looking down +Z");

        // Plane-normal group.
        var perpX = btn("⊥X", e -> session.geometry.slicePlane.set(SlicePlane.axisX()),
            "Slice plane perpendicular to X");
        var perpY = btn("⊥Y", e -> session.geometry.slicePlane.set(SlicePlane.axisY()),
            "Slice plane perpendicular to Y");
        var perpZ = btn("⊥Z", e -> session.geometry.slicePlane.set(SlicePlane.axisZ()),
            "Slice plane perpendicular to Z");

        var snap = btn("Snap", e -> editor.snapPlaneNormalToAxis(8),
            "Snap plane normal to nearest principal axis");
        var reset = btn("Reset", e -> {
            session.geometry.slicePlane.set(SlicePlane.axisY());
            editor.resetView();
        }, "Reset plane to y=0 and camera to ISO");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var cameraGroup = new HBox(2, iso, px, py, pz);
        var planeGroup  = new HBox(2, perpX, perpY, perpZ);
        var sep1 = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        var sep2 = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        var bar = new HBox(6, cameraGroup, sep1, planeGroup, sep2, snap, reset, spacer, colourMenu, labels);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.getStyleClass().add("shell-tool-strip");
        return bar;
    }

    private static Button btn(String label, javafx.event.EventHandler<javafx.event.ActionEvent> handler, String tooltip) {
        var b = new Button(label);
        b.setOnAction(handler);
        b.setStyle("-fx-font-size: 10; -fx-padding: 2 6 2 6;");
        b.setMinWidth(Region.USE_PREF_SIZE);  // prevent ellipsis collapse
        if (tooltip != null) b.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return b;
    }

    /* ── Heatmap rendering ──────────────────────────────────────────── */

    private void redrawHeatmap() {
        double w = heatmap.getWidth();
        double h = heatmap.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = heatmap.getGraphicsContext2D();
        g.setFill(StudioTheme.BG);
        g.fillRect(0, 0, w, h);

        var session = paneContext.session();
        var sim = session.document.simulation.get();
        var pulse = session.document.currentPulse.get();
        if (sim == null || pulse == null) return;

        boolean continuumPresent = sim.primaryContinuousMagnetisation() != null;
        double plotWidth = Math.max(1, w - PAD_LEFT - PAD_RIGHT);
        double plotHeight = Math.max(1, h - PAD_TOP - PAD_BOTTOM);

        if (!continuumPresent) {
            // No continuous-magnetisation substance — the per-voxel sweep
            // would have nothing to draw. Tell the user.
            g.save();
            g.setFill(Color.color(0.55, 0.58, 0.62, 0.9));
            g.setFont(StudioTheme.UI_8);
            g.setTextAlign(TextAlignment.CENTER);
            double cx = PAD_LEFT + plotWidth / 2;
            double cy = PAD_TOP + plotHeight / 2;
            g.fillText("No continuous-magnetisation substance in the FOV.", cx, cy - 6);
            g.fillText("Slice heatmap shows static B-field geometry only.", cx, cy + 8);
            g.restore();
            g.setTextAlign(TextAlignment.LEFT);
            drawAxesLabels(g, w, h);
            return;
        }

        var snapshot = session.geometry.shadingSnapshot.get();
        boolean signalProjectionAvailable = MagnetisationColouringSupport.isSignalProjectionAvailable(
            sim.segments(), pulse, session.timeAxis.cursor.time.get());

        if (snapshot != null && !session.colouring.isOff()) {
            drawShading(g, snapshot, session.colouring, signalProjectionAvailable, plotWidth, plotHeight);
        } else if (snapshot == null && session.geometry.shadingComputing.get()) {
            g.setFill(Color.gray(0.2, 0.5));
            g.fillText("Computing shading…", PAD_LEFT + 8, PAD_TOP + 14);
        }

        drawAxesLabels(g, w, h);

        // Project IsochromatEntries onto the plane and draw those within
        // a slice-thickness band as filled circles.
        var plane = session.geometry.slicePlane.get();
        if (plane != null && snapshot != null) {
            drawPointsOnPlane(g, plane, snapshot, plotWidth, plotHeight);
        }
    }

    private void drawShading(
        GraphicsContext g,
        GeometryShadingSnapshot snapshot,
        MagnetisationColouringViewModel colouring,
        boolean signalProjectionAvailable,
        double plotWidth,
        double plotHeight
    ) {
        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();
        if (us.isEmpty() || vs.isEmpty()) return;
        double uMin = us.get(0), uMax = us.get(us.size() - 1);
        double vMin = vs.get(0), vMax = vs.get(vs.size() - 1);
        double uSpan = Math.max(1e-30, uMax - uMin);
        double vSpan = Math.max(1e-30, vMax - vMin);

        for (int i = 0; i < us.size(); i++) {
            double u0 = us.get(i);
            double uPrev = i > 0 ? 0.5 * (us.get(i - 1) + u0) : uMin;
            double uNext = i < us.size() - 1 ? 0.5 * (u0 + us.get(i + 1)) : uMax;
            double x0 = PAD_LEFT + (uPrev - uMin) / uSpan * plotWidth;
            double x1 = PAD_LEFT + (uNext - uMin) / uSpan * plotWidth;
            for (int j = 0; j < vs.size(); j++) {
                double v0 = vs.get(j);
                double vPrev = j > 0 ? 0.5 * (vs.get(j - 1) + v0) : vMin;
                double vNext = j < vs.size() - 1 ? 0.5 * (v0 + vs.get(j + 1)) : vMax;
                // v ↑ on screen → flip y.
                double y1 = PAD_TOP + plotHeight * (1 - (vPrev - vMin) / vSpan);
                double y0 = PAD_TOP + plotHeight * (1 - (vNext - vMin) / vSpan);
                var cell = snapshot.cells()[i][j];
                var fill = shadingColour(colouring, cell, signalProjectionAvailable);
                if (fill == null) continue;
                g.setFill(fill);
                g.fillRect(Math.min(x0, x1), Math.min(y0, y1),
                    Math.abs(x1 - x0) + 1, Math.abs(y1 - y0) + 1);
            }
        }
    }

    private void drawAxesLabels(GraphicsContext g, double width, double height) {
        var snapshot = paneContext.session().geometry.shadingSnapshot.get();
        var plane = paneContext.session().geometry.slicePlane.get();
        g.setStroke(Color.color(0, 0, 0, 0.20));
        g.setLineWidth(0.5);
        double plotWidth = Math.max(1, width - PAD_LEFT - PAD_RIGHT);
        double plotHeight = Math.max(1, height - PAD_TOP - PAD_BOTTOM);
        g.strokeLine(PAD_LEFT, PAD_TOP, PAD_LEFT, PAD_TOP + plotHeight);
        g.strokeLine(PAD_LEFT, PAD_TOP + plotHeight, PAD_LEFT + plotWidth, PAD_TOP + plotHeight);

        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_BOLD_7);
        g.setGlobalAlpha(0.7);

        String uLabel = "U", vLabel = "V", unitU = "MM", unitV = "MM";
        if (snapshot != null && !snapshot.uMetres().isEmpty() && !snapshot.vMetres().isEmpty()) {
            double uExtent = Math.max(
                Math.abs(snapshot.uMetres().get(0)),
                Math.abs(snapshot.uMetres().get(snapshot.uMetres().size() - 1)));
            double vExtent = Math.max(
                Math.abs(snapshot.vMetres().get(0)),
                Math.abs(snapshot.vMetres().get(snapshot.vMetres().size() - 1)));
            unitU = pickUnitSuffix(uExtent);
            unitV = pickUnitSuffix(vExtent);
            if (plane != null) {
                uLabel = "U  (" + describeAxis(plane.u()) + ")";
                vLabel = "V  (" + describeAxis(plane.v()) + ")";
            }
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(uLabel + " [" + unitU + "]", PAD_LEFT + plotWidth / 2, height - 4);
        g.save();
        g.translate(10, PAD_TOP + plotHeight / 2);
        g.rotate(-90);
        g.fillText(vLabel + " [" + unitV + "]", 0, 0);
        g.restore();
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);

        // Tick labels — 5 ticks per axis.
        if (snapshot != null && !snapshot.uMetres().isEmpty()) {
            drawTicksU(g, snapshot, plotWidth, plotHeight, unitU);
            drawTicksV(g, snapshot, plotWidth, plotHeight, unitV);
        }
    }

    private void drawTicksU(GraphicsContext g, GeometryShadingSnapshot snapshot,
                            double plotWidth, double plotHeight, String unit) {
        var us = snapshot.uMetres();
        double uMin = us.get(0), uMax = us.get(us.size() - 1);
        double uSpan = Math.max(1e-30, uMax - uMin);
        double scale = unitScale(unit);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_7);
        for (int t = 0; t <= 4; t++) {
            double u = uMin + uSpan * t / 4.0;
            double x = PAD_LEFT + (u - uMin) / uSpan * plotWidth;
            g.fillText(String.format("%.1f", u * scale), x, PAD_TOP + plotHeight + 12);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(x, PAD_TOP, x, PAD_TOP + plotHeight);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawTicksV(GraphicsContext g, GeometryShadingSnapshot snapshot,
                            double plotWidth, double plotHeight, String unit) {
        var vs = snapshot.vMetres();
        double vMin = vs.get(0), vMax = vs.get(vs.size() - 1);
        double vSpan = Math.max(1e-30, vMax - vMin);
        double scale = unitScale(unit);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_7);
        for (int t = 0; t <= 4; t++) {
            double v = vMin + vSpan * t / 4.0;
            double y = PAD_TOP + plotHeight * (1 - (v - vMin) / vSpan);
            g.fillText(String.format("%.1f", v * scale), PAD_LEFT - 4, y + 3);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(PAD_LEFT, y, PAD_LEFT + plotWidth, y);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawPointsOnPlane(
        GraphicsContext g, SlicePlane plane, GeometryShadingSnapshot snapshot,
        double plotWidth, double plotHeight
    ) {
        var session = paneContext.session();
        var sim = session.document.simulation.get();
        if (sim == null) return;
        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();
        if (us.isEmpty() || vs.isEmpty()) return;
        double uMin = us.get(0), uMax = us.get(us.size() - 1);
        double vMin = vs.get(0), vMax = vs.get(vs.size() - 1);
        double uSpan = Math.max(1e-30, uMax - uMin);
        double vSpan = Math.max(1e-30, vMax - vMin);
        // Off-plane points fade with distance from the plane, scaled to the
        // substance bounding-box diagonal so a 1 µm box's NV centres at
        // z=-50 nm fade similarly to a 30 mm box's isochromat 2 mm off the
        // plane.
        double hx = 0, hy = 0, hz = 0;
        for (var s : sim.substances()) {
            var hv = s.halfExtent();
            hx = Math.max(hx, hv.x());
            hy = Math.max(hy, hv.y());
            hz = Math.max(hz, hv.z());
        }
        double fovDiag = Math.max(1e-30, Math.sqrt(hx * hx + hy * hy + hz * hz));

        for (var entry : session.points.entries) {
            if (!entry.visible()) continue;
            var p = entry.position();
            double d = plane.signedDistance(p);
            // Project onto plane and compute (u, v).
            var proj = plane.project(p);
            var rel = proj.minus(plane.origin());
            double uCoord = rel.dot(plane.u());
            double vCoord = rel.dot(plane.v());
            if (uCoord < uMin || uCoord > uMax || vCoord < vMin || vCoord > vMax) continue;
            double sx = PAD_LEFT + (uCoord - uMin) / uSpan * plotWidth;
            double sy = PAD_TOP + plotHeight * (1 - (vCoord - vMin) / vSpan);
            // Fade off-plane: opacity 1.0 on the plane → 0.2 at half FOV diagonal.
            double depthFraction = Math.min(1.0, Math.abs(d) / Math.max(1e-30, fovDiag));
            double depthOpacity = MathUtil.clamp(1.0 - 0.8 * depthFraction, 0.2, 1.0);
            boolean selected = session.selection.isSelected(entry.id());
            double radius = selected ? 5.0 : 4.0;
            g.setGlobalAlpha(depthOpacity);
            g.setFill(entry.colour());
            g.fillOval(sx - radius, sy - radius, 2 * radius, 2 * radius);
            g.setStroke(selected ? Color.BLACK : Color.color(0, 0, 0, 0.4));
            g.setLineWidth(selected ? 1.4 : 0.8);
            g.strokeOval(sx - radius, sy - radius, 2 * radius, 2 * radius);
            if (session.geometry.showLabels.get()) {
                g.setFill(StudioTheme.TX);
                g.setFont(StudioTheme.UI_7);
                g.fillText(entry.name(), sx + 7, sy - 5);
            }
            g.setGlobalAlpha(1.0);
        }
    }

    private void onHeatmapClick(javafx.scene.input.MouseEvent e) {
        if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
        var session = paneContext.session();
        var snapshot = session.geometry.shadingSnapshot.get();
        var plane = session.geometry.slicePlane.get();
        if (snapshot == null || plane == null) return;
        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();
        if (us.isEmpty() || vs.isEmpty()) return;
        double plotWidth = Math.max(1, heatmap.getWidth() - PAD_LEFT - PAD_RIGHT);
        double plotHeight = Math.max(1, heatmap.getHeight() - PAD_TOP - PAD_BOTTOM);
        double uMin = us.get(0), uMax = us.get(us.size() - 1);
        double vMin = vs.get(0), vMax = vs.get(vs.size() - 1);
        double uSpan = Math.max(1e-30, uMax - uMin);
        double vSpan = Math.max(1e-30, vMax - vMin);
        // Hit-test every projected point; pick the nearest within 12 px.
        IsochromatEntry best = null;
        double bestDistSq = 12 * 12;
        for (var entry : session.points.entries) {
            if (!entry.visible()) continue;
            var p = entry.position();
            var proj = plane.project(p);
            var rel = proj.minus(plane.origin());
            double uCoord = rel.dot(plane.u());
            double vCoord = rel.dot(plane.v());
            if (uCoord < uMin || uCoord > uMax || vCoord < vMin || vCoord > vMax) continue;
            double sx = PAD_LEFT + (uCoord - uMin) / uSpan * plotWidth;
            double sy = PAD_TOP + plotHeight * (1 - (vCoord - vMin) / vSpan);
            double dx = e.getX() - sx, dy = e.getY() - sy;
            double dsq = dx * dx + dy * dy;
            if (dsq <= bestDistSq) {
                bestDistSq = dsq;
                best = entry;
            }
        }
        if (best != null) {
            session.selection.setSingle(best.id());
        } else {
            session.selection.clear();
        }
    }

    private void onHeatmapHover(javafx.scene.input.MouseEvent e) {
        var session = paneContext.session();
        var snapshot = session.geometry.shadingSnapshot.get();
        var plane = session.geometry.slicePlane.get();
        if (snapshot == null || plane == null) {
            setPaneStatus("");
            return;
        }
        var us = snapshot.uMetres();
        var vs = snapshot.vMetres();
        if (us.isEmpty() || vs.isEmpty()) return;
        double plotWidth = Math.max(1, heatmap.getWidth() - PAD_LEFT - PAD_RIGHT);
        double plotHeight = Math.max(1, heatmap.getHeight() - PAD_TOP - PAD_BOTTOM);
        double uMin = us.get(0), uMax = us.get(us.size() - 1);
        double vMin = vs.get(0), vMax = vs.get(vs.size() - 1);
        double u = uMin + (e.getX() - PAD_LEFT) / plotWidth * (uMax - uMin);
        double v = vMin + (1 - (e.getY() - PAD_TOP) / plotHeight) * (vMax - vMin);
        if (e.getX() < PAD_LEFT || e.getX() > PAD_LEFT + plotWidth
            || e.getY() < PAD_TOP || e.getY() > PAD_TOP + plotHeight) {
            setPaneStatus("");
            return;
        }
        var world = plane.sampleAt(u, v);
        setPaneStatus(
            String.format("u=%.2f µm  v=%.2f µm", u * 1e6, v * 1e6),
            String.format("(x=%.2f, y=%.2f, z=%.2f) µm",
                world.x() * 1e6, world.y() * 1e6, world.z() * 1e6),
            "plane n=" + describeAxis(plane.normal())
        );
    }

    private ContextMenu buildHeatmapMenu(double mouseX, double mouseY) {
        var session = paneContext.session();
        var menu = new ContextMenu();
        var perpX = new MenuItem("Plane ⊥ X");
        perpX.setOnAction(e -> session.geometry.slicePlane.set(SlicePlane.axisX()));
        var perpY = new MenuItem("Plane ⊥ Y");
        perpY.setOnAction(e -> session.geometry.slicePlane.set(SlicePlane.axisY()));
        var perpZ = new MenuItem("Plane ⊥ Z");
        perpZ.setOnAction(e -> session.geometry.slicePlane.set(SlicePlane.axisZ()));
        var snap = new MenuItem("Snap normal to nearest axis");
        snap.setOnAction(e -> editor.snapPlaneNormalToAxis(8));
        var resetView = new MenuItem("Reset 3-D view");
        resetView.setOnAction(e -> editor.resetView());
        menu.getItems().addAll(perpX, perpY, perpZ, new SeparatorMenuItem(),
            snap, MagnetisationColouringControls.newMenu(session.colouring),
            new SeparatorMenuItem(), resetView);
        return menu;
    }

    private void showCanvasContextMenu(ContextMenu menu, double screenX, double screenY) {
        menu.show(heatmap, screenX, screenY);
    }

    /* ── Helpers ─────────────────────────────────────────────────────── */

    private static Color shadingColour(
        MagnetisationColouringViewModel colouring,
        GeometryShadingSnapshot.CellSample cell,
        boolean signalProjectionAvailable
    ) {
        if (colouring.isOff()) return null;
        double brightness = MathUtil.clamp(
            MagnetisationColouringSupport.brightnessValue(
                colouring.brightnessSource.get(),
                cell.mPerp(),
                cell.signalProjection(),
                signalProjectionAvailable),
            0, 1);
        if (brightness < 0.04) return null;
        return switch (colouring.hueSource.get()) {
            case PHASE -> ColourUtil.hue2color(cell.phaseDeg(), brightness);
            case NONE -> colouring.brightnessSource.get() == MagnetisationColouringViewModel.BrightnessSource.NONE
                ? null
                : ColourUtil.monochrome(brightness);
        };
    }

    /** Compact axis description — "+ẑ", "−x̂", etc., or "(.., .., ..)" for arbitrary. */
    private static String describeAxis(Vec3 axis) {
        double tol = 0.02;
        double ax = Math.abs(axis.x()), ay = Math.abs(axis.y()), az = Math.abs(axis.z());
        if (ax > 1 - tol && ay < tol && az < tol) return axis.x() > 0 ? "+x̂" : "−x̂";
        if (ay > 1 - tol && ax < tol && az < tol) return axis.y() > 0 ? "+ŷ" : "−ŷ";
        if (az > 1 - tol && ax < tol && ay < tol) return axis.z() > 0 ? "+ẑ" : "−ẑ";
        return String.format("(%+.2f, %+.2f, %+.2f)", axis.x(), axis.y(), axis.z());
    }

    private static String pickUnitSuffix(double extent) {
        if (extent < 1e-3) return "ΜM";   // µm
        if (extent < 1.0) return "MM";
        return "M";
    }

    private static double unitScale(String unit) {
        return switch (unit) {
            case "ΜM" -> 1e6;
            case "MM" -> 1e3;
            default -> 1.0;
        };
    }

    /** Test accessor for the embedded 3-D canvas. */
    Geometry3DCanvas editorForTest() { return editor; }
}
