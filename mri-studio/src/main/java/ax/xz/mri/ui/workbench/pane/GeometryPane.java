package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.canvas.ColorUtil;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.GeometryShadingSnapshot;
import ax.xz.mri.ui.viewmodel.GeometryViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/** Geometry/cross-section pane using async shading and stable point ids. */
public class GeometryPane extends CanvasWorkbenchPane {
    private final Slider zRangeSlider = new Slider(5, 300, 80);
    private ax.xz.mri.ui.model.IsochromatId draggingId;
    private double dragOffsetR;
    private double dragOffsetZ;

    public GeometryPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Geometry");

        var shadeMode = new ComboBox<GeometryViewModel.ShadeMode>();
        shadeMode.getItems().addAll(GeometryViewModel.ShadeMode.values());
        shadeMode.valueProperty().bindBidirectional(paneContext.session().geometry.shadeMode);
        var labels = new CheckBox("Labels");
        labels.selectedProperty().bindBidirectional(paneContext.session().geometry.showLabels);
        setToolNodes(shadeMode, labels);

        zRangeSlider.setOrientation(Orientation.VERTICAL);
        zRangeSlider.valueProperty().bindBidirectional(paneContext.session().geometry.halfHeight);
        setRight(zRangeSlider);

        bindRedraw(
            paneContext.session().document.blochData,
            paneContext.session().document.currentPulse,
            paneContext.session().viewport.tC,
            paneContext.session().points.entries,
            paneContext.session().selection.selectedIds,
            paneContext.session().geometry.halfHeight,
            paneContext.session().geometry.shadeMode,
            paneContext.session().geometry.showLabels,
            paneContext.session().geometry.showSliceOverlay,
            paneContext.session().geometry.shadingSnapshot,
            paneContext.session().geometry.shadingComputing,
            paneContext.session().geometry.signalModeBlocked
        );

        canvas.setOnMousePressed(event -> {
            if (event.isSecondaryButtonDown()) return;
            var entry = findEntry(event.getX(), event.getY());
            if (entry != null) {
                paneContext.session().selection.setSingle(entry.id());
                if (!entry.locked()) {
                    draggingId = entry.id();
                    var rz = screenToRz(event.getX(), event.getY());
                    dragOffsetR = entry.r() - rz[0];
                    dragOffsetZ = entry.z() - rz[1];
                }
            } else {
                paneContext.session().selection.clear();
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (draggingId == null) return;
            var rz = screenToRz(event.getX(), event.getY());
            paneContext.session().points.move(draggingId, Math.max(0, rz[0] + dragOffsetR), rz[1] + dragOffsetZ);
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> draggingId = null);
        canvas.setOnMouseMoved(event -> updateStatus(event.getX(), event.getY()));
        canvas.setOnScroll(event -> paneContext.session().geometry.halfHeight.set(
            MathUtil.clamp(
                paneContext.session().geometry.halfHeight.get() * (event.getDeltaY() > 0 ? 0.85 : 1.18),
                5,
                300
            )));
        canvas.setOnContextMenuRequested(event -> {
            var entry = findEntry(event.getX(), event.getY());
            var menu = entry != null ? buildEntryMenu(entry) : buildBackgroundMenu(event.getX(), event.getY());
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(javafx.scene.canvas.GraphicsContext g, double width, double height) {
        g.setFill(StudioTheme.BG);
        g.fillRect(0, 0, width, height);

        var data = paneContext.session().document.blochData.get();
        var pulse = paneContext.session().document.currentPulse.get();
        if (data == null || pulse == null || data.field() == null) return;

        var field = data.field();
        double rMax = field.rMm[field.rMm.length - 1];
        double halfHeight = paneContext.session().geometry.halfHeight.get();
        double padLeft = 28;
        double padRight = 8;
        double padTop = 8;
        double padBottom = 20;
        double plotWidth = width - padLeft - padRight;
        double plotHeight = height - padTop - padBottom;

        if (paneContext.session().geometry.shadeMode.get() != GeometryViewModel.ShadeMode.OFF) {
            var snapshot = paneContext.session().geometry.shadingSnapshot.get();
            if (snapshot != null) {
                drawShading(g, snapshot, rMax, halfHeight, padLeft, padTop, plotWidth, plotHeight);
            } else if (paneContext.session().geometry.shadingComputing.get()) {
                g.setFill(Color.gray(0.2, 0.5));
                g.fillText("Computing shading\u2026", padLeft + 8, padTop + 14);
            }
        }

        if (paneContext.session().geometry.showSliceOverlay.get()) {
            double sliceHalf = (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3;
            double yTop = padTop + plotHeight * (1 - (sliceHalf - (-halfHeight)) / (2 * halfHeight));
            double yBottom = padTop + plotHeight * (1 - (-sliceHalf - (-halfHeight)) / (2 * halfHeight));
            g.setStroke(Color.web("#2e7d32"));
            g.setGlobalAlpha(0.5);
            g.setLineWidth(1);
            g.setLineDashes(4, 3);
            g.strokeLine(padLeft, yTop, padLeft + plotWidth, yTop);
            g.strokeLine(padLeft, yBottom, padLeft + plotWidth, yBottom);
            g.setLineDashes();
            g.setFill(Color.web("#2e7d32"));
            g.fillText("slice", padLeft + 2, (yTop + yBottom) / 2 + 3);
            g.setGlobalAlpha(1);
        }

        g.setStroke(Color.color(0, 0, 0, 0.2));
        g.setLineWidth(0.5);
        g.beginPath();
        g.moveTo(padLeft, padTop);
        g.lineTo(padLeft, padTop + plotHeight);
        g.lineTo(padLeft + plotWidth, padTop + plotHeight);
        g.stroke();

        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_7);
        g.setTextAlign(TextAlignment.RIGHT);
        int zTickStep = halfHeight > 50 ? 50 : halfHeight > 20 ? 10 : halfHeight > 8 ? 5 : 2;
        for (double z = -Math.floor(halfHeight / zTickStep) * zTickStep; z <= halfHeight; z += zTickStep) {
            double y = padTop + plotHeight * (1 - (z - (-halfHeight)) / (2 * halfHeight));
            if (y < padTop + 2 || y > padTop + plotHeight - 2) continue;
            g.fillText(String.valueOf((int) z), padLeft - 3, y + 3);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(padLeft, y, padLeft + plotWidth, y);
        }

        g.setTextAlign(TextAlignment.CENTER);
        int rTickStep = rMax > 60 ? 20 : rMax > 30 ? 10 : 5;
        for (double r = 0; r <= rMax; r += rTickStep) {
            double x = padLeft + (r / rMax) * plotWidth;
            if (x < padLeft + 2 || x > padLeft + plotWidth - 2) continue;
            g.fillText(String.valueOf((int) r), x, padTop + plotHeight + 11);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(x, padTop, x, padTop + plotHeight);
        }

        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_BOLD_7);
        g.setGlobalAlpha(0.6);
        g.fillText("r [mm]", padLeft + plotWidth / 2, height - 2);
        g.save();
        g.translate(8, padTop + plotHeight / 2);
        g.rotate(-90);
        g.fillText("z [mm]", 0, 0);
        g.restore();
        g.setGlobalAlpha(1);
        g.setTextAlign(TextAlignment.LEFT);

        for (var entry : paneContext.session().points.entries) {
            double x = padLeft + (entry.r() / rMax) * plotWidth;
            double y = padTop + plotHeight * (1 - (entry.z() - (-halfHeight)) / (2 * halfHeight));
            if (y < padTop - 5 || y > padTop + plotHeight + 5 || x < padLeft - 5 || x > padLeft + plotWidth + 5) continue;
            boolean selected = paneContext.session().selection.isSelected(entry.id());
            double radius = selected ? 5 : 4;
            g.setFill(entry.colour());
            g.setGlobalAlpha(entry.visible() ? 0.9 : 0.2);
            g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            g.setStroke(selected ? Color.BLACK : Color.color(0, 0, 0, 0.4));
            g.setLineWidth(selected ? 1.5 : 1);
            g.setGlobalAlpha(entry.visible() ? 0.9 : 0.2);
            g.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
            if (paneContext.session().geometry.showLabels.get()) {
                g.setFill(StudioTheme.TX);
                g.setFont(StudioTheme.UI_7);
                g.fillText(entry.name(), x + 6, y + 3);
            }
            g.setGlobalAlpha(1);
        }
    }

    private void drawShading(javafx.scene.canvas.GraphicsContext g, GeometryShadingSnapshot snapshot,
                             double rMax, double halfHeight,
                             double padLeft, double padTop, double plotWidth, double plotHeight) {
        g.save();
        g.beginPath();
        g.rect(padLeft, padTop, plotWidth, plotHeight);
        g.clip();
        int radialSamples = snapshot.cells().length;
        int zCount = snapshot.zSamples().size();
        for (int radialIndex = 0; radialIndex < radialSamples; radialIndex++) {
            double r0 = (double) radialIndex / (radialSamples - 1) * rMax;
            double r1 = radialIndex < radialSamples - 1
                ? (double) (radialIndex + 1) / (radialSamples - 1) * rMax
                : rMax;
            double x0 = padLeft + (r0 / rMax) * plotWidth;
            double x1 = radialIndex < radialSamples - 1
                ? padLeft + (r1 / rMax) * plotWidth
                : padLeft + plotWidth;
            for (int zIndex = 0; zIndex < zCount; zIndex++) {
                double z0 = snapshot.zSamples().get(zIndex);
                double zPrev = zIndex > 0 ? 0.5 * (snapshot.zSamples().get(zIndex - 1) + z0) : z0;
                double zNext = zIndex < zCount - 1 ? 0.5 * (z0 + snapshot.zSamples().get(zIndex + 1)) : z0;
                double zTop = zIndex < zCount - 1 ? zNext : z0 + (z0 - zPrev);
                double zBottom = zIndex > 0 ? zPrev : z0 - (zNext - z0);
                if (zTop < -halfHeight || zBottom > halfHeight) continue;
                var cell = snapshot.cells()[radialIndex][zIndex];
                double y0 = padTop + plotHeight * (1 - (Math.min(zTop, halfHeight) - (-halfHeight)) / (2 * halfHeight));
                double y1 = padTop + plotHeight * (1 - (Math.max(zBottom, -halfHeight) - (-halfHeight)) / (2 * halfHeight));
                g.setFill(ColorUtil.hue2color(cell.phaseDeg(), MathUtil.clamp(cell.brightness(), 0, 1)));
                g.fillRect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0) + 1, Math.abs(y1 - y0) + 1);
            }
        }
        g.restore();
    }

    private void updateStatus(double mouseX, double mouseY) {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) {
            setPaneStatus("No geometry loaded");
            return;
        }
        var rz = screenToRz(mouseX, mouseY);
        long visible = paneContext.session().points.entries.stream().filter(IsochromatEntry::visible).count();
        String suffix = paneContext.session().geometry.signalModeBlocked.get()
            ? " | Signal shading blocked during RF"
            : paneContext.session().geometry.statusMessage.get();
        if (suffix == null) suffix = "";
        setPaneStatus(String.format(
            "r=%.1f z=%.1f mm | %d points (%d visible)%s",
            rz[0],
            rz[1],
            paneContext.session().points.entries.size(),
            visible,
            suffix.isBlank() ? "" : suffix
        ));
    }

    private ContextMenu buildBackgroundMenu(double mouseX, double mouseY) {
        var rz = screenToRz(mouseX, mouseY);
        var menu = new ContextMenu();
        var add = new MenuItem(String.format("Add Point (r=%.1f, z=%.1f)", rz[0], rz[1]));
        add.setOnAction(event -> paneContext.session().points.addUserPoint(rz[0], rz[1],
            String.format("r=%.1f z=%.1f", rz[0], rz[1])));
        var reset = new MenuItem("Reset Defaults");
        reset.setOnAction(event -> paneContext.session().points.resetToDefaults());
        var clearUser = new MenuItem("Clear User Points");
        clearUser.setOnAction(event -> paneContext.session().points.clearUserPoints());
        menu.getItems().addAll(add, new SeparatorMenuItem(), reset, clearUser);
        return menu;
    }

    private ContextMenu buildEntryMenu(IsochromatEntry entry) {
        var menu = new ContextMenu();
        var selectOnly = new MenuItem("Select Only");
        selectOnly.setOnAction(event -> paneContext.session().selection.setSingle(entry.id()));
        var toggle = new MenuItem(entry.visible() ? "Hide" : "Show");
        toggle.setOnAction(event -> paneContext.session().points.toggleVisibility(entry.id()));
        var duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(event -> {
            paneContext.session().selection.setSingle(entry.id());
            paneContext.session().points.duplicateSelected();
        });
        var lock = new MenuItem(entry.locked() ? "Unlock" : "Lock");
        lock.setOnAction(event -> paneContext.session().points.setLocked(entry.id(), !entry.locked()));
        var delete = new MenuItem("Delete");
        delete.setOnAction(event -> paneContext.session().points.remove(entry.id()));
        menu.getItems().addAll(selectOnly, toggle, duplicate, lock, new SeparatorMenuItem(), delete);
        return menu;
    }

    private IsochromatEntry findEntry(double mouseX, double mouseY) {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) return null;
        double rMax = data.field().rMm[data.field().rMm.length - 1];
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double padLeft = 28;
        double padRight = 8;
        double padTop = 8;
        double padBottom = 20;
        double plotWidth = width - padLeft - padRight;
        double plotHeight = height - padTop - padBottom;
        double halfHeight = paneContext.session().geometry.halfHeight.get();

        IsochromatEntry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var entry : paneContext.session().points.entries) {
            double x = padLeft + (entry.r() / rMax) * plotWidth;
            double y = padTop + plotHeight * (1 - (entry.z() - (-halfHeight)) / (2 * halfHeight));
            double distance = Math.hypot(mouseX - x, mouseY - y);
            if (distance < 10 && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double[] screenToRz(double mouseX, double mouseY) {
        var data = paneContext.session().document.blochData.get();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double padLeft = 28;
        double padRight = 8;
        double padTop = 8;
        double padBottom = 20;
        double plotWidth = width - padLeft - padRight;
        double plotHeight = height - padTop - padBottom;
        double rMax = data.field().rMm[data.field().rMm.length - 1];
        double halfHeight = paneContext.session().geometry.halfHeight.get();
        double r = (mouseX - padLeft) / plotWidth * rMax;
        double z = (1 - (mouseY - padTop) / plotHeight) * 2 * halfHeight - halfHeight;
        return new double[]{Math.max(0, r), z};
    }
}
