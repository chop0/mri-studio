package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.canvas.Projection;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;

import java.util.Comparator;

import static ax.xz.mri.ui.canvas.Projection.project;

/** Bloch sphere pane rewritten against stable ids and pane-local view state. */
public class SphereWorkbenchPane extends CanvasWorkbenchPane {
    private double dragX;
    private double dragY;
    private boolean rotating;

    public SphereWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Bloch Sphere");

        var front = new Button("Front");
        front.setOnAction(event -> paneContext.session().sphere.setPreset(0, 0));
        var top = new Button("Top");
        top.setOnAction(event -> paneContext.session().sphere.setPreset(0, Math.PI / 2));
        var iso = new Button("ISO");
        iso.setOnAction(event -> paneContext.session().sphere.setPreset(0.6, 0.5));
        var reset = new Button("Reset");
        reset.setOnAction(event -> paneContext.session().sphere.reset());
        var projection = new CheckBox("|M\u22a5|");
        projection.selectedProperty().bindBidirectional(paneContext.session().sphere.showProjection);
        setToolNodes(front, top, iso, reset, projection);

        bindRedraw(
            paneContext.session().points.entries,
            paneContext.session().selection.selectedIds,
            paneContext.session().sphere.theta,
            paneContext.session().sphere.phi,
            paneContext.session().sphere.zoom,
            paneContext.session().sphere.showProjection,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC
        );

        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
            var hit = findNearestEntry(event.getX(), event.getY());
            if (hit != null) {
                paneContext.session().selection.setSingle(hit.id());
            }
            rotating = hit == null || event.isPrimaryButtonDown();
        });
        canvas.setOnMouseDragged(event -> {
            if (!rotating) return;
            paneContext.session().sphere.addTheta((event.getX() - dragX) * 0.008);
            paneContext.session().sphere.addPhi((event.getY() - dragY) * 0.008);
            dragX = event.getX();
            dragY = event.getY();
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> rotating = false);
        canvas.setOnScroll(event -> paneContext.session().sphere.addZoom(event.getDeltaY() > 0 ? 1.1 : 0.91));
        canvas.setOnMouseMoved(event -> updateStatus(event.getX(), event.getY()));
        canvas.setOnContextMenuRequested(event -> {
            var hit = findNearestEntry(event.getX(), event.getY());
            var menu = hit != null ? buildEntryMenu(hit) : buildBackgroundMenu();
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(javafx.scene.canvas.GraphicsContext g, double width, double height) {
        double centreX = width / 2;
        double centreY = height / 2;
        double theta = paneContext.session().sphere.theta.get();
        double phi = paneContext.session().sphere.phi.get();
        double scale = Math.min(width, height) * 0.37 * paneContext.session().sphere.zoom.get();

        g.setFill(StudioTheme.BG);
        g.fillRect(0, 0, width, height);

        drawRing(g, theta, phi, scale, centreX, centreY, 'e');
        drawRing(g, theta, phi, scale, centreX, centreY, 'x');
        drawRing(g, theta, phi, scale, centreX, centreY, 'y');

        double[][] axes = {
            {1.15, 0, 0},
            {0, 1.15, 0},
            {0, 0, 1.15},
        };
        String[] labels = {"Mx", "My", "Mz"};
        Color[] colours = {Color.web("#d32f2f"), Color.web("#2e7d32"), Color.web("#1565c0")};
        double[] origin = project(0, 0, 0, theta, phi, scale, centreX, centreY);
        for (int axisIndex = 0; axisIndex < axes.length; axisIndex++) {
            var point = project(axes[axisIndex][0], axes[axisIndex][1], axes[axisIndex][2], theta, phi, scale, centreX, centreY);
            double depth = (1 + point[2]) / 2.0;
            g.setStroke(colours[axisIndex]);
            g.setLineWidth(0.5 + 0.5 * depth);
            g.setGlobalAlpha(0.25 + 0.55 * depth);
            g.strokeLine(origin[0], origin[1], point[0], point[1]);
            g.setFill(colours[axisIndex]);
            g.setFont(javafx.scene.text.Font.font(StudioTheme.UI_9.getFamily(),
                javafx.scene.text.FontWeight.SEMI_BOLD, 10 + depth));
            g.setGlobalAlpha(0.4 + 0.5 * depth);
            g.fillText(labels[axisIndex], point[0] + 4, point[1] - 3);
            g.setGlobalAlpha(1);
        }

        double windowStart = paneContext.session().viewport.tS.get();
        double windowEnd = paneContext.session().viewport.tE.get();
        double cursorTime = paneContext.session().viewport.tC.get();
        boolean showProjection = paneContext.session().sphere.showProjection.get();

        for (var entry : paneContext.session().points.entries) {
            if (!entry.visible() || entry.trajectory() == null) continue;
            boolean selected = paneContext.session().selection.isSelected(entry.id());
            var trajectory = entry.trajectory();
            int pointCount = trajectory.pointCount();

            int visibleStart = -1;
            int visibleEnd = -1;
            for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                double t = trajectory.tAt(pointIndex);
                if (t >= windowStart && t <= windowEnd) {
                    if (visibleStart < 0) visibleStart = pointIndex;
                    visibleEnd = pointIndex;
                }
            }
            if (visibleStart < 0 || visibleEnd <= visibleStart) continue;

            int segmentStart = visibleStart;
            for (int pointIndex = visibleStart + 1; pointIndex <= visibleEnd + 1; pointIndex++) {
                boolean segmentEnded = pointIndex > visibleEnd
                    || trajectory.isRfAt(pointIndex) != trajectory.isRfAt(pointIndex - 1);
                if (!segmentEnded) continue;
                if (pointIndex - segmentStart >= 2) {
                    boolean pulseSegment = trajectory.isRfAt(segmentStart);
                    double sumDepth = 0;
                    for (int sampleIndex = segmentStart; sampleIndex < pointIndex; sampleIndex++) {
                        var projected = project(
                            trajectory.mxAt(sampleIndex),
                            trajectory.myAt(sampleIndex),
                            trajectory.mzAt(sampleIndex),
                            theta, phi, scale, centreX, centreY
                        );
                        sumDepth += projected[2];
                    }
                    double fade = 0.3 + 0.7 * (1 + sumDepth / (pointIndex - segmentStart)) / 2.0;
                    g.setStroke(entry.colour());
                    g.setLineWidth(selected ? 2.4 : (pulseSegment ? 1.8 : 1.0));
                    g.setGlobalAlpha((pulseSegment ? 0.8 : 0.1) * fade * (selected ? 1.0 : 0.9));
                    g.beginPath();
                    for (int sampleIndex = segmentStart; sampleIndex < pointIndex; sampleIndex++) {
                        var projected = project(
                            trajectory.mxAt(sampleIndex),
                            trajectory.myAt(sampleIndex),
                            trajectory.mzAt(sampleIndex),
                            theta, phi, scale, centreX, centreY
                        );
                        if (sampleIndex == segmentStart) g.moveTo(projected[0], projected[1]);
                        else g.lineTo(projected[0], projected[1]);
                    }
                    g.stroke();
                    g.setGlobalAlpha(1);
                }
                segmentStart = pointIndex;
            }

            var state = trajectory.interpolateAt(cursorTime);
            if (state == null) continue;
            double mx = state.mx();
            double my = state.my();
            double mz = state.mz();
            double magnitude = Math.sqrt(mx * mx + my * my + mz * mz);
            double mPerp = Math.sqrt(mx * mx + my * my);
            double ux = magnitude > 1e-6 ? mx / magnitude : 0;
            double uy = magnitude > 1e-6 ? my / magnitude : 0;
            double uz = magnitude > 1e-6 ? mz / magnitude : 0;
            var projectedState = project(ux, uy, uz, theta, phi, scale, centreX, centreY);
            double fade = 0.5 + 0.5 * (1 + projectedState[2]) / 2.0;
            double radius = (selected ? 5 : 3) + 2 * fade;

            g.setFill(entry.colour());
            g.setGlobalAlpha(fade);
            g.fillOval(projectedState[0] - radius, projectedState[1] - radius, radius * 2, radius * 2);
            g.setStroke(selected ? Color.BLACK : Color.color(1, 1, 1, 0.6));
            g.setLineWidth(selected ? 1.6 : 1.0);
            g.setGlobalAlpha(selected ? 1.0 : 0.7 * fade);
            g.strokeOval(projectedState[0] - radius, projectedState[1] - radius, radius * 2, radius * 2);
            g.setGlobalAlpha(1);

            if (showProjection && mPerp > 0.01) {
                var projectedPerp = project(mx, my, 0, theta, phi, scale, centreX, centreY);
                g.setLineDashes(3, 3);
                g.setStroke(entry.colour());
                g.setGlobalAlpha(0.35);
                g.setLineWidth(selected ? 1.4 : 1.0);
                g.strokeLine(projectedState[0], projectedState[1], projectedPerp[0], projectedPerp[1]);
                g.setLineDashes();
                g.setStroke(entry.colour());
                g.setLineWidth(selected ? 2.0 : 1.5);
                g.setGlobalAlpha(0.5 + 0.3 * mPerp);
                double projectionRadius = 2 + 4 * mPerp;
                g.strokeOval(projectedPerp[0] - projectionRadius, projectedPerp[1] - projectionRadius,
                    projectionRadius * 2, projectionRadius * 2);
                g.setGlobalAlpha(1);
            }
        }
    }

    private void updateStatus(double mouseX, double mouseY) {
        double theta = paneContext.session().sphere.theta.get();
        double phi = paneContext.session().sphere.phi.get();
        double zoom = paneContext.session().sphere.zoom.get();
        var hit = findNearestEntry(mouseX, mouseY);
        String suffix = hit != null ? " | selected: " + hit.name() : "";
        setPaneStatus(String.format(
            "\u03b8=%.1f\u00b0 \u03c6=%.1f\u00b0 zoom=%.0f%%%s",
            Math.toDegrees(theta), Math.toDegrees(phi), zoom * 100, suffix
        ));
    }

    private ContextMenu buildBackgroundMenu() {
        var menu = new ContextMenu();
        var reset = new MenuItem("Reset View");
        reset.setOnAction(event -> paneContext.session().sphere.reset());
        var front = new MenuItem("Front View");
        front.setOnAction(event -> paneContext.session().sphere.setPreset(0, 0));
        var top = new MenuItem("Top View");
        top.setOnAction(event -> paneContext.session().sphere.setPreset(0, Math.PI / 2));
        var iso = new MenuItem("ISO View");
        iso.setOnAction(event -> paneContext.session().sphere.setPreset(0.6, 0.5));
        var toggleProjection = new MenuItem(
            paneContext.session().sphere.showProjection.get() ? "Hide |M\u22a5| Projection" : "Show |M\u22a5| Projection"
        );
        toggleProjection.setOnAction(event ->
            paneContext.session().sphere.showProjection.set(!paneContext.session().sphere.showProjection.get()));
        menu.getItems().addAll(reset, new SeparatorMenuItem(), front, top, iso, new SeparatorMenuItem(), toggleProjection);
        return menu;
    }

    private ContextMenu buildEntryMenu(IsochromatEntry entry) {
        var menu = new ContextMenu();
        var selectOnly = new MenuItem("Select Only");
        selectOnly.setOnAction(event -> paneContext.session().selection.setSingle(entry.id()));
        var hide = new MenuItem(entry.visible() ? "Hide Trace" : "Show Trace");
        hide.setOnAction(event -> paneContext.session().points.toggleVisibility(entry.id()));
        var isolate = new MenuItem("Show Only This Trace");
        isolate.setOnAction(event -> {
            for (var other : paneContext.session().points.entries) {
                if (other.id().equals(entry.id())) {
                    if (!other.visible()) paneContext.session().points.toggleVisibility(other.id());
                } else if (other.visible()) {
                    paneContext.session().points.toggleVisibility(other.id());
                }
            }
            paneContext.session().selection.setSingle(entry.id());
        });
        var delete = new MenuItem("Delete Point");
        delete.setOnAction(event -> paneContext.session().points.remove(entry.id()));
        menu.getItems().addAll(selectOnly, hide, isolate, new SeparatorMenuItem(), delete);
        return menu;
    }

    private IsochromatEntry findNearestEntry(double mouseX, double mouseY) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double theta = paneContext.session().sphere.theta.get();
        double phi = paneContext.session().sphere.phi.get();
        double scale = Math.min(width, height) * 0.37 * paneContext.session().sphere.zoom.get();
        double centreX = width / 2;
        double centreY = height / 2;
        double cursorTime = paneContext.session().viewport.tC.get();

        return paneContext.session().points.entries.stream()
            .filter(entry -> entry.trajectory() != null)
            .map(entry -> {
                var state = entry.trajectory().interpolateAt(cursorTime);
                if (state == null) return null;
                double magnitude = Math.sqrt(state.mx() * state.mx() + state.my() * state.my() + state.mz() * state.mz());
                double mx = magnitude > 1e-6 ? state.mx() / magnitude : 0;
                double my = magnitude > 1e-6 ? state.my() / magnitude : 0;
                double mz = magnitude > 1e-6 ? state.mz() / magnitude : 0;
                var projected = project(mx, my, mz, theta, phi, scale, centreX, centreY);
                double distance = Math.hypot(mouseX - projected[0], mouseY - projected[1]);
                return new Object() {
                    final IsochromatEntry entryRef = entry;
                    final double d = distance;
                };
            })
            .filter(hit -> hit != null && hit.d < 10)
            .min(Comparator.comparingDouble(hit -> hit.d))
            .map(hit -> hit.entryRef)
            .orElse(null);
    }

    private void drawRing(javafx.scene.canvas.GraphicsContext graphics, double theta, double phi,
                          double scale, double centreX, double centreY, char plane) {
        for (int pass = 0; pass < 2; pass++) {
            graphics.beginPath();
            boolean started = false;
            for (int index = 0; index <= 80; index++) {
                double angle = index / 80.0 * Math.PI * 2;
                double[] projected = switch (plane) {
                    case 'e' -> Projection.project(Math.cos(angle), Math.sin(angle), 0, theta, phi, scale, centreX, centreY);
                    case 'x' -> Projection.project(Math.cos(angle), 0, Math.sin(angle), theta, phi, scale, centreX, centreY);
                    default -> Projection.project(0, Math.cos(angle), Math.sin(angle), theta, phi, scale, centreX, centreY);
                };
                boolean inFront = projected[2] > 0;
                if ((pass == 0 && !inFront) || (pass == 1 && inFront)) {
                    if (!started) {
                        graphics.moveTo(projected[0], projected[1]);
                        started = true;
                    } else {
                        graphics.lineTo(projected[0], projected[1]);
                    }
                } else {
                    started = false;
                }
            }
            graphics.setStroke(pass == 1 ? Color.color(0, 0, 0, 0.18) : Color.color(0, 0, 0, 0.06));
            graphics.setLineWidth(pass == 1 ? 0.6 : 0.4);
            graphics.stroke();
        }
    }
}
