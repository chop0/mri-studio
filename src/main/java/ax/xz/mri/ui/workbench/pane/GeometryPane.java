package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.canvas.ColourUtil;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.model.IsochromatId;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.GeometryShadingSnapshot;
import ax.xz.mri.ui.viewmodel.GeometryViewModel;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringSupport;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.scene.Cursor;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/** Geometry/cross-section pane with a shared scrub-bar interaction model and a true z-axis viewport. */
public class GeometryPane extends CanvasWorkbenchPane {
    private static final double PAD_LEFT = 28;
    private static final double PAD_TOP = 8;
    private static final double PAD_BOTTOM = 20;
    private static final double PAD_RIGHT = 8;
    private static final double SCRUB_GAP = 8;
    private static final double SCRUB_WIDTH = 12;

    private final AxisScrubBar.Interaction zRangeInteraction;
    private IsochromatId draggingId;
    private double dragOffsetR;
    private double dragOffsetZ;
    private boolean draggingReference;
    private boolean hoveringPlot;
    private double hoveredR;
    private double hoveredZ;

    public GeometryPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Geometry");

        var colourMenu = MagnetisationColouringControls.newMenuButton(paneContext.session().colouring);
        var labels = new CheckBox("Labels");
        labels.selectedProperty().bindBidirectional(paneContext.session().geometry.showLabels);
        setToolNodes(colourMenu, labels);

        var geometry = paneContext.session().geometry;
        zRangeInteraction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.VERTICAL,
            AxisScrubBar.WindowModel.of(
                this::zDomainStart, this::zDomainEnd,
                geometry::visibleStart, geometry::visibleEnd,
                (start, end) -> geometry.setVisibleRange(start, end, zDomainStart(), zDomainEnd()),
                (anchor, factor) -> geometry.zoomVisibleRangeAround(anchor, factor, zDomainStart(), zDomainEnd()),
                () -> geometry.fitVisibleRange(zDomainStart(), zDomainEnd()),
                "Zoom Out Z Axis"
            )
        );

        bindRedraw(
            paneContext.session().document.blochData,
            paneContext.session().document.currentPulse,
            paneContext.session().viewport.tC,
            paneContext.session().points.entries,
            paneContext.session().selection.selectedIds,
            paneContext.session().geometry.halfHeight,
            paneContext.session().geometry.zCenter,
            paneContext.session().colouring.hueSource,
            paneContext.session().colouring.brightnessSource,
            paneContext.session().geometry.showLabels,
            paneContext.session().geometry.showSliceOverlay,
            paneContext.session().geometry.shadingSnapshot,
            paneContext.session().geometry.shadingComputing,
            paneContext.session().geometry.statusMessage,
            paneContext.session().reference.enabled,
            paneContext.session().reference.r,
            paneContext.session().reference.z,
            paneContext.session().reference.trajectory
        );

        canvas.setOnMousePressed(event -> {
            if (event.isSecondaryButtonDown()) return;
            if (zRangeInteraction.handlePress(scrubBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateCursor(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }

            if (referenceHandleContains(event.getX(), event.getY())) {
                draggingReference = true;
                var rz = screenToRz(event.getX(), event.getY());
                paneContext.session().reference.moveTo(rz[0], rz[1]);
                updateHover(event.getX(), event.getY());
                updateCursor(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                return;
            }

            var entry = findEntry(event.getX(), event.getY());
            if (entry != null) {
                paneContext.session().selection.setSingle(entry.id());
                if (!entry.locked()) {
                    draggingId = entry.id();
                    var rz = screenToRz(event.getX(), event.getY());
                    dragOffsetR = entry.r() - rz[0];
                    dragOffsetZ = entry.z() - rz[1];
                }
            } else if (plotBounds().contains(event.getX(), event.getY())) {
                paneContext.session().selection.clear();
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (zRangeInteraction.handleDrag(scrubBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateCursor(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }
            if (draggingReference) {
                var rz = screenToRz(event.getX(), event.getY());
                paneContext.session().reference.moveTo(rz[0], rz[1]);
            }
            if (draggingId != null) {
                var rz = screenToRz(event.getX(), event.getY());
                paneContext.session().points.move(draggingId, Math.max(0, rz[0] + dragOffsetR), rz[1] + dragOffsetZ);
            }
            updateHover(event.getX(), event.getY());
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> {
            draggingId = null;
            draggingReference = false;
            zRangeInteraction.handleRelease();
        });
        canvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseExited(event -> {
            hoveringPlot = false;
            updateCursor(-1, -1);
            scheduleRedraw();
        });
        canvas.setOnScroll(event -> {
            if (zRangeInteraction.handleScroll(scrubBounds(), event)) {
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }
            if (plotBounds().contains(event.getX(), event.getY())) {
                paneContext.session().geometry.zoomVisibleRangeAround(
                    screenToRz(event.getX(), event.getY())[1],
                    event.getDeltaY() > 0 ? 0.85 : 1.18,
                    zDomainStart(),
                    zDomainEnd()
                );
            }
        });
        canvas.setOnContextMenuRequested(event -> {
            ContextMenu menu;
            if (scrubBounds().contains(event.getX(), event.getY())) {
                menu = buildZRangeMenu();
            } else if (referenceHandleContains(event.getX(), event.getY())) {
                menu = buildReferenceMenu();
            } else {
                var entry = findEntry(event.getX(), event.getY());
                menu = entry != null ? buildEntryMenu(entry) : buildBackgroundMenu(event.getX(), event.getY());
            }
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
        double zMin = paneContext.session().geometry.visibleStart();
        double zMax = paneContext.session().geometry.visibleEnd();
        double plotWidth = plotWidth(width);
        double plotHeight = plotHeight(height);
        if (plotWidth <= 0 || plotHeight <= 0) return;

        boolean signalProjectionAvailable = MagnetisationColouringSupport.isSignalProjectionAvailable(
            field,
            pulse,
            paneContext.session().viewport.tC.get()
        );
        if (!paneContext.session().colouring.isOff()) {
            var snapshot = paneContext.session().geometry.shadingSnapshot.get();
            if (snapshot != null) {
                drawShading(
                    g,
                    snapshot,
                    paneContext.session().colouring,
                    signalProjectionAvailable,
                    rMax,
                    zMin,
                    zMax,
                    PAD_LEFT,
                    PAD_TOP,
                    plotWidth,
                    plotHeight
                );
            } else if (paneContext.session().geometry.shadingComputing.get()) {
                g.setFill(Color.gray(0.2, 0.5));
                g.fillText("Computing shading\u2026", PAD_LEFT + 8, PAD_TOP + 14);
            }
        }

        if (paneContext.session().geometry.showSliceOverlay.get()) {
            double sliceHalf = (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3;
            double yTop = zToPixel(sliceHalf, zMin, zMax, plotHeight);
            double yBottom = zToPixel(-sliceHalf, zMin, zMax, plotHeight);
            g.setFill(Color.color(0.18, 0.49, 0.2, 0.05));
            g.fillRect(PAD_LEFT, Math.min(yTop, yBottom), plotWidth, Math.abs(yBottom - yTop));
            g.setStroke(Color.web("#2e7d32"));
            g.setGlobalAlpha(0.55);
            g.setLineWidth(1);
            g.setLineDashes(4, 3);
            g.strokeLine(PAD_LEFT, yTop, PAD_LEFT + plotWidth, yTop);
            g.strokeLine(PAD_LEFT, yBottom, PAD_LEFT + plotWidth, yBottom);
            g.setLineDashes();
            g.setFill(Color.web("#2e7d32"));
            g.fillText("slice", PAD_LEFT + 2, (yTop + yBottom) / 2 + 3);
            g.setGlobalAlpha(1);
        }

        drawAxes(g, rMax, zMin, zMax, plotWidth, plotHeight, height);

        for (var entry : paneContext.session().points.entries) {
            double x = rToPixel(entry.r(), rMax, plotWidth);
            double y = zToPixel(entry.z(), zMin, zMax, plotHeight);
            if (y < PAD_TOP - 5 || y > PAD_TOP + plotHeight + 5 || x < PAD_LEFT - 5 || x > PAD_LEFT + plotWidth + 5) continue;
            boolean selected = paneContext.session().selection.isSelected(entry.id());
            double radius = selected ? 5.5 : 4.2;
            if (selected) {
                g.setFill(Color.color(0, 0, 0, 0.10));
                g.fillOval(x - radius - 2, y - radius - 2, (radius + 2) * 2, (radius + 2) * 2);
            }
            g.setFill(entry.colour());
            g.setGlobalAlpha(entry.visible() ? 0.92 : 0.18);
            g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            g.setStroke(selected ? Color.BLACK : Color.color(0, 0, 0, 0.35));
            g.setLineWidth(selected ? 1.5 : 0.9);
            g.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
            if (paneContext.session().geometry.showLabels.get()) {
                drawPointLabel(g, entry, x + 8, y - 8, selected);
            }
            g.setGlobalAlpha(1);
        }

        drawReferenceMarker(g, rMax, zMin, zMax, plotWidth, plotHeight);

        if (hoveringPlot) {
            double hoverX = rToPixel(hoveredR, rMax, plotWidth);
            double hoverY = zToPixel(hoveredZ, zMin, zMax, plotHeight);
            g.setStroke(Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.35));
            g.setLineWidth(0.8);
            g.setLineDashes(4, 3);
            g.strokeLine(hoverX, PAD_TOP, hoverX, PAD_TOP + plotHeight);
            g.strokeLine(PAD_LEFT, hoverY, PAD_LEFT + plotWidth, hoverY);
            g.setLineDashes();
            drawBadge(g, PAD_LEFT + plotWidth - 48, PAD_TOP + 4, String.format("z=%.1f mm", hoveredZ), StudioTheme.AC);
        }

        AxisScrubBar.draw(
            g,
            scrubBounds(plotWidth, plotHeight),
            AxisScrubBar.Spec.vertical(
                zDomainStart(),
                zDomainEnd(),
                zMin,
                zMax,
                sliceSpans(),
                List.of()
            )
        );
        drawBadge(
            g,
            PAD_LEFT + plotWidth - 36,
            PAD_TOP + plotHeight - 18,
            String.format("[%.0f, %.0f] mm", zMin, zMax),
            Color.web("#1565c0")
        );
    }

    private void drawAxes(
        javafx.scene.canvas.GraphicsContext g,
        double rMax,
        double zMin,
        double zMax,
        double plotWidth,
        double plotHeight,
        double totalHeight
    ) {
        g.setStroke(Color.color(0, 0, 0, 0.2));
        g.setLineWidth(0.5);
        g.beginPath();
        g.moveTo(PAD_LEFT, PAD_TOP);
        g.lineTo(PAD_LEFT, PAD_TOP + plotHeight);
        g.lineTo(PAD_LEFT + plotWidth, PAD_TOP + plotHeight);
        g.stroke();

        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_7);
        g.setTextAlign(TextAlignment.RIGHT);
        for (double z = niceStart(zMin, niceZTick(zMax - zMin)); z <= zMax + 1e-6; z += niceZTick(zMax - zMin)) {
            double y = zToPixel(z, zMin, zMax, plotHeight);
            if (y < PAD_TOP + 2 || y > PAD_TOP + plotHeight - 2) continue;
            g.fillText(String.valueOf((int) Math.round(z)), PAD_LEFT - 3, y + 3);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(PAD_LEFT, y, PAD_LEFT + plotWidth, y);
        }

        g.setTextAlign(TextAlignment.CENTER);
        int rTickStep = rMax > 60 ? 20 : rMax > 30 ? 10 : 5;
        for (double r = 0; r <= rMax + 1e-6; r += rTickStep) {
            double x = rToPixel(r, rMax, plotWidth);
            if (x < PAD_LEFT + 2 || x > PAD_LEFT + plotWidth - 2) continue;
            g.fillText(String.valueOf((int) r), x, PAD_TOP + plotHeight + 11);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(x, PAD_TOP, x, PAD_TOP + plotHeight);
        }

        g.setFill(StudioTheme.TX2);
        g.setFont(StudioTheme.UI_BOLD_7);
        g.setGlobalAlpha(0.6);
        g.fillText("r [mm]", PAD_LEFT + plotWidth / 2, totalHeight - 2);
        g.save();
        g.translate(8, PAD_TOP + plotHeight / 2);
        g.rotate(-90);
        g.fillText("z [mm]", 0, 0);
        g.restore();
        g.setGlobalAlpha(1);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawPointLabel(javafx.scene.canvas.GraphicsContext g, IsochromatEntry entry, double x, double y, boolean selected) {
        double width = Math.max(42, entry.name().length() * 5.0 + 10);
        g.setFill(Color.color(1, 1, 1, selected ? 0.85 : 0.72));
        g.fillRoundRect(x - 3, y - 10, width, 12, 5, 5);
        g.setStroke(Color.color(entry.colour().getRed(), entry.colour().getGreen(), entry.colour().getBlue(), selected ? 0.55 : 0.28));
        g.setLineWidth(selected ? 0.9 : 0.6);
        g.strokeRoundRect(x - 3, y - 10, width, 12, 5, 5);
        g.setFill(StudioTheme.TX);
        g.setFont(StudioTheme.UI_7);
        g.fillText(entry.name(), x + 2, y - 1.5);
    }

    private void drawShading(
        javafx.scene.canvas.GraphicsContext g,
        GeometryShadingSnapshot snapshot,
        MagnetisationColouringViewModel colouring,
        boolean signalProjectionAvailable,
        double rMax,
        double zMin,
        double zMax,
        double padLeft,
        double padTop,
        double plotWidth,
        double plotHeight
    ) {
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
                if (zTop < zMin || zBottom > zMax) continue;
                var cell = snapshot.cells()[radialIndex][zIndex];
                double y0 = padTop + plotHeight * (1 - (Math.min(zTop, zMax) - zMin) / (zMax - zMin));
                double y1 = padTop + plotHeight * (1 - (Math.max(zBottom, zMin) - zMin) / (zMax - zMin));
                Color fill = shadingColour(colouring, cell, signalProjectionAvailable);
                if (fill == null) continue;
                g.setFill(fill);
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
        if (scrubBounds().contains(mouseX, mouseY)) {
            String colouringSuffix = " | " + paneContext.session().colouring.statusLabel();
            setPaneStatus(String.format(
                "z view=[%.1f, %.1f] mm | double-click to zoom out%s%s",
                paneContext.session().geometry.visibleStart(),
                paneContext.session().geometry.visibleEnd(),
                referenceSuffix(),
                colouringSuffix
            ));
            return;
        }
        var rz = screenToRz(mouseX, mouseY);
        long visible = paneContext.session().points.entries.stream().filter(IsochromatEntry::visible).count();
        boolean signalProjectionAvailable = MagnetisationColouringSupport.isSignalProjectionAvailable(
            data.field(),
            paneContext.session().document.currentPulse.get(),
            paneContext.session().viewport.tC.get()
        );
        StringBuilder suffix = new StringBuilder();
        suffix.append(" | ").append(paneContext.session().colouring.statusLabel());
        if (MagnetisationColouringSupport.isSignalProjectionFallbackActive(
            paneContext.session().colouring.brightnessSource.get(),
            signalProjectionAvailable
        )) {
            suffix.append(" | RF: using excitation brightness");
        }
        String statusMessage = paneContext.session().geometry.statusMessage.get();
        if (statusMessage != null && !statusMessage.isBlank()) {
            suffix.append(" | ").append(statusMessage);
        }
        setPaneStatus(String.format(
            "r=%.1f z=%.1f mm | %d points (%d visible)%s%s",
            rz[0],
            rz[1],
            paneContext.session().points.entries.size(),
            visible,
            referenceSuffix(),
            suffix
        ));
    }

    private ContextMenu buildBackgroundMenu(double mouseX, double mouseY) {
        var rz = screenToRz(mouseX, mouseY);
        var menu = new ContextMenu();
        var add = new MenuItem(String.format("Add Point (r=%.1f, z=%.1f)", rz[0], rz[1]));
        add.setOnAction(event -> paneContext.session().points.addUserPoint(rz[0], rz[1], String.format("r=%.1f z=%.1f", rz[0], rz[1])));
        var setBasis = new MenuItem(String.format("Set Basis Frame Here (r=%.1f, z=%.1f)", rz[0], rz[1]));
        setBasis.setOnAction(event -> paneContext.session().reference.setReference(rz[0], rz[1]));
        var clearBasis = new MenuItem("Clear Basis Frame");
        clearBasis.setDisable(!paneContext.session().reference.enabled.get());
        clearBasis.setOnAction(event -> paneContext.session().reference.clear());
        var resetDefaults = new MenuItem("Reset Defaults");
        resetDefaults.setOnAction(event -> paneContext.session().points.resetToDefaults());
        var clearUser = new MenuItem("Clear User Points");
        clearUser.setOnAction(event -> paneContext.session().points.clearUserPoints());
        menu.getItems().addAll(
            add,
            setBasis,
            clearBasis,
            new SeparatorMenuItem(),
            MagnetisationColouringControls.newMenu(paneContext.session().colouring),
            new SeparatorMenuItem(),
            zRangeInteraction.newResetMenuItem(),
            new SeparatorMenuItem(),
            resetDefaults,
            clearUser
        );
        return menu;
    }

    private ContextMenu buildZRangeMenu() {
        var menu = new ContextMenu();
        var label = new MenuItem("Z Axis");
        label.setDisable(true);
        var centreSlice = new MenuItem("Centre on Slice");
        centreSlice.setOnAction(event -> paneContext.session().geometry.setVisibleRange(
            -paneContext.session().geometry.halfHeight.get(),
            paneContext.session().geometry.halfHeight.get(),
            zDomainStart(),
            zDomainEnd()
        ));
        menu.getItems().addAll(
            label,
            new SeparatorMenuItem(),
            MagnetisationColouringControls.newMenu(paneContext.session().colouring),
            new SeparatorMenuItem(),
            zRangeInteraction.newResetMenuItem(),
            centreSlice
        );
        return menu;
    }

    private ContextMenu buildEntryMenu(IsochromatEntry entry) {
        var menu = new ContextMenu();
        var selectOnly = new MenuItem("Select Only");
        selectOnly.setOnAction(event -> paneContext.session().selection.setSingle(entry.id()));
        var useAsBasis = new MenuItem("Use As Basis Frame");
        useAsBasis.setOnAction(event -> paneContext.session().reference.setReference(entry.r(), entry.z()));
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
        menu.getItems().addAll(
            selectOnly,
            useAsBasis,
            toggle,
            duplicate,
            lock,
            new SeparatorMenuItem(),
            MagnetisationColouringControls.newMenu(paneContext.session().colouring),
            new SeparatorMenuItem(),
            delete,
            new SeparatorMenuItem(),
            zRangeInteraction.newResetMenuItem()
        );
        return menu;
    }

    private ContextMenu buildReferenceMenu() {
        var reference = paneContext.session().reference;
        var menu = new ContextMenu();
        var label = new MenuItem(String.format("Basis Frame (r=%.1f, z=%.1f)", reference.r.get(), reference.z.get()));
        label.setDisable(true);
        var clear = new MenuItem("Clear Basis Frame");
        clear.setOnAction(event -> reference.clear());
        menu.getItems().addAll(
            label,
            new SeparatorMenuItem(),
            MagnetisationColouringControls.newMenu(paneContext.session().colouring),
            clear,
            new SeparatorMenuItem(),
            zRangeInteraction.newResetMenuItem()
        );
        return menu;
    }

    private IsochromatEntry findEntry(double mouseX, double mouseY) {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null || !plotBounds().contains(mouseX, mouseY)) return null;
        double rMax = data.field().rMm[data.field().rMm.length - 1];
        double plotWidth = plotWidth(canvas.getWidth());
        double plotHeight = plotHeight(canvas.getHeight());
        double zMin = paneContext.session().geometry.visibleStart();
        double zMax = paneContext.session().geometry.visibleEnd();

        IsochromatEntry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var entry : paneContext.session().points.entries) {
            double x = rToPixel(entry.r(), rMax, plotWidth);
            double y = zToPixel(entry.z(), zMin, zMax, plotHeight);
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
        if (data == null || data.field() == null) {
            return new double[]{0, paneContext.session().geometry.zCenter.get()};
        }
        double plotWidth = plotWidth(canvas.getWidth());
        double plotHeight = plotHeight(canvas.getHeight());
        double rMax = data.field().rMm[data.field().rMm.length - 1];
        double zMin = paneContext.session().geometry.visibleStart();
        double zMax = paneContext.session().geometry.visibleEnd();
        double r = (mouseX - PAD_LEFT) / Math.max(1, plotWidth) * rMax;
        double z = zMax - ((mouseY - PAD_TOP) / Math.max(1, plotHeight)) * (zMax - zMin);
        return new double[]{Math.max(0, r), MathUtil.clamp(z, zMin, zMax)};
    }

    private void updateHover(double mouseX, double mouseY) {
        boolean nextHover = plotBounds().contains(mouseX, mouseY);
        if (!nextHover) {
            if (hoveringPlot) {
                hoveringPlot = false;
                scheduleRedraw();
            }
            return;
        }
        var rz = screenToRz(mouseX, mouseY);
        hoveringPlot = true;
        hoveredR = rz[0];
        hoveredZ = rz[1];
        scheduleRedraw();
    }

    private void updateCursor(double mouseX, double mouseY) {
        if (scrubBounds().contains(mouseX, mouseY)) {
            canvas.setCursor(zRangeInteraction.cursor(scrubBounds(), mouseX, mouseY));
            return;
        }
        if (draggingReference) {
            canvas.setCursor(Cursor.CLOSED_HAND);
            return;
        }
        if (referenceHandleContains(mouseX, mouseY)) {
            canvas.setCursor(Cursor.OPEN_HAND);
            return;
        }
        if (draggingId != null) {
            canvas.setCursor(Cursor.CLOSED_HAND);
            return;
        }
        canvas.setCursor(findEntry(mouseX, mouseY) != null ? Cursor.OPEN_HAND : Cursor.CROSSHAIR);
    }

    private List<AxisScrubBar.Span> sliceSpans() {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) return List.of();
        double sliceHalf = (data.field().sliceHalf != null ? data.field().sliceHalf : 0.005) * 1e3;
        var spans = new ArrayList<AxisScrubBar.Span>();
        spans.add(new AxisScrubBar.Span(-sliceHalf, sliceHalf, Color.web("#2e7d32"), 0.14));
        return spans;
    }

    private void drawBadge(javafx.scene.canvas.GraphicsContext g, double centerX, double y, String text, Color accent) {
        g.setFont(StudioTheme.UI_7);
        double width = Math.max(48, text.length() * 4.9);
        double x = MathUtil.clamp(centerX - width / 2, PAD_LEFT + 2, canvas.getWidth() - PAD_RIGHT - SCRUB_WIDTH - width);
        g.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.10));
        g.fillRoundRect(x, y, width, 12, 6, 6);
        g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.45));
        g.setLineWidth(0.6);
        g.strokeRoundRect(x, y, width, 12, 6, 6);
        g.setFill(Color.color(0.18, 0.2, 0.24, 0.9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x + width / 2, y + 8.2);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawReferenceMarker(
        javafx.scene.canvas.GraphicsContext g,
        double rMax,
        double zMin,
        double zMax,
        double plotWidth,
        double plotHeight
    ) {
        var reference = paneContext.session().reference;
        if (!reference.enabled.get()) return;
        double x = rToPixel(reference.r.get(), rMax, plotWidth);
        double y = zToPixel(reference.z.get(), zMin, zMax, plotHeight);
        if (!plotBounds().contains(x, y)) return;

        Color accent = Color.web("#f57c00");
        double radius = draggingReference ? 7.0 : 6.0;
        g.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.12));
        g.fillOval(x - radius - 3, y - radius - 3, (radius + 3) * 2, (radius + 3) * 2);
        g.setStroke(accent);
        g.setLineWidth(1.6);
        g.strokePolygon(
            new double[]{x, x + radius, x, x - radius},
            new double[]{y - radius, y, y + radius, y},
            4
        );
        g.strokeLine(x - radius - 3, y, x + radius + 3, y);
        g.strokeLine(x, y - radius - 3, x, y + radius + 3);
        drawBadge(g, Math.min(x + 28, PAD_LEFT + plotWidth - 18), Math.max(PAD_TOP + 4, y - 18), "Basis", accent);
    }

    private boolean referenceHandleContains(double mouseX, double mouseY) {
        var reference = paneContext.session().reference;
        if (!reference.enabled.get()) return false;
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) return false;
        double plotWidth = plotWidth(canvas.getWidth());
        double plotHeight = plotHeight(canvas.getHeight());
        double x = rToPixel(reference.r.get(), data.field().rMm[data.field().rMm.length - 1], plotWidth);
        double y = zToPixel(reference.z.get(), paneContext.session().geometry.visibleStart(), paneContext.session().geometry.visibleEnd(), plotHeight);
        return plotBounds().contains(x, y) && Math.hypot(mouseX - x, mouseY - y) <= 11.0;
    }

    private String referenceSuffix() {
        var reference = paneContext.session().reference;
        if (!reference.enabled.get()) return "";
        return String.format(" | basis=(%.1f, %.1f)", reference.r.get(), reference.z.get());
    }

    private double zDomainStart() {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) return -80;
        return data.field().zMm[0];
    }

    private double zDomainEnd() {
        var data = paneContext.session().document.blochData.get();
        if (data == null || data.field() == null) return 80;
        return data.field().zMm[data.field().zMm.length - 1];
    }

    private AxisScrubBar.Bounds scrubBounds() {
        return scrubBounds(plotWidth(canvas.getWidth()), plotHeight(canvas.getHeight()));
    }

    private AxisScrubBar.Bounds scrubBounds(double plotWidth, double plotHeight) {
        return new AxisScrubBar.Bounds(PAD_LEFT + plotWidth + SCRUB_GAP, PAD_TOP, SCRUB_WIDTH, plotHeight);
    }

    private AxisScrubBar.Bounds plotBounds() {
        return new AxisScrubBar.Bounds(PAD_LEFT, PAD_TOP, plotWidth(canvas.getWidth()), plotHeight(canvas.getHeight()));
    }

    private static double plotWidth(double totalWidth) {
        return Math.max(1, totalWidth - PAD_LEFT - PAD_RIGHT - SCRUB_GAP - SCRUB_WIDTH);
    }

    private static double plotHeight(double totalHeight) {
        return Math.max(1, totalHeight - PAD_TOP - PAD_BOTTOM);
    }

    private static double rToPixel(double r, double rMax, double plotWidth) {
        return PAD_LEFT + (r / Math.max(1e-9, rMax)) * plotWidth;
    }

    private static double zToPixel(double z, double zMin, double zMax, double plotHeight) {
        return PAD_TOP + (1 - (z - zMin) / Math.max(1e-9, zMax - zMin)) * plotHeight;
    }

    private static int niceZTick(double span) {
        return span > 120 ? 20 : span > 60 ? 10 : span > 24 ? 5 : span > 12 ? 2 : 1;
    }

    private static double niceStart(double value, double step) {
        return Math.ceil(value / step) * step;
    }

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
                signalProjectionAvailable
            ),
            0,
            1
        );
        return switch (colouring.hueSource.get()) {
            case PHASE -> ColourUtil.hue2color(cell.phaseDeg(), brightness);
            case NONE -> colouring.brightnessSource.get() == MagnetisationColouringViewModel.BrightnessSource.NONE
                ? null
                : ColourUtil.monochrome(brightness);
        };
    }
}
