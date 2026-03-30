package ax.xz.mri.ui.pane;

import ax.xz.mri.model.simulation.Isochromat;
import ax.xz.mri.service.simulation.BlochSimulator;
import ax.xz.mri.state.AppState;
import ax.xz.mri.state.CrossSectionState.ShadeMode;
import ax.xz.mri.ui.canvas.ColorUtil;
import ax.xz.mri.ui.framework.CanvasPane;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.util.MathUtil;
import javafx.beans.Observable;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashSet;

import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * r-z geometry view: shows field geometry, isochromat positions, and optionally
 * colour-coded |M⊥| or signal shading at the cursor time.
 * Renamed from CrossSectionPane. Shading is off by default.
 */
public class GeometryViewPane extends CanvasPane {

    private static final int    RADIAL_SAMPLES = 18;
    private static final double INNER_Z_STEP   = 0.5;
    private static final double MID_Z_STEP     = 1.0;
    private static final int    OUTER_BANDS    = 24;

    private final Slider zRangeSlider = new Slider(5, 300, 80);

    public GeometryViewPane(AppState s) {
        super(s);
        buildToolbar();
        installMouseHandlers();
        onAttached();
    }

    @Override public String getPaneId()    { return "geometry-view"; }
    @Override public String getPaneTitle() { return "Geometry"; }

    private void buildToolbar() {
        // Shading controls in a local toolbar
        var shadeCb = new ComboBox<String>();
        shadeCb.getItems().addAll("Off", "|M\u22a5|", "Signal");
        shadeCb.setValue("Off");
        shadeCb.setOnAction(e -> {
            var v = shadeCb.getValue();
            appState.crossSection.shadingEnabled.set(!"Off".equals(v));
            if ("|M\u22a5|".equals(v)) appState.crossSection.shadeMode.set(ShadeMode.MP);
            else if ("Signal".equals(v)) appState.crossSection.shadeMode.set(ShadeMode.SIGNAL);
        });

        var showMpChk = new CheckBox("|M\u22a5| proj");
        showMpChk.selectedProperty().bindBidirectional(appState.crossSection.showMpProj);

        var toolbar = new HBox(6,
            new Label("Shade:"), shadeCb, showMpChk);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(2, 4, 2, 4));
        setTop(toolbar);

        // Z-range slider on right edge
        zRangeSlider.setOrientation(Orientation.VERTICAL);
        zRangeSlider.setPrefWidth(20);
        zRangeSlider.valueProperty().bindBidirectional(appState.crossSection.halfHeight);
        setRight(zRangeSlider);
    }

    @Override
    protected Observable[] getRedrawTriggers() {
        return new Observable[]{
            appState.document.currentPulse,
            appState.viewport.tC,
            appState.crossSection.halfHeight,
            appState.crossSection.shadeMode,
            appState.crossSection.shadingEnabled,
            appState.isochromats.isochromats,
        };
    }

    @Override
    protected void installMouseHandlers() {
        final Isochromat[] dragging = {null};
        final double[]     dragOff  = {0, 0};

        canvas.setOnMousePressed(e -> {
            if (e.isSecondaryButtonDown()) return;
            var iso = findIsochromat(e.getX(), e.getY());
            if (iso != null) {
                dragging[0] = iso;
                var rz = screenToRZ(e.getX(), e.getY());
                dragOff[0] = iso.r() - rz[0];
                dragOff[1] = iso.z() - rz[1];
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (dragging[0] == null) return;
            var rz = screenToRZ(e.getX(), e.getY());
            appState.isochromats.moveIsochromat(dragging[0],
                Math.max(0, rz[0] + dragOff[0]), rz[1] + dragOff[1]);
            dragging[0] = appState.isochromats.isochromats.stream()
                .filter(i -> i.name().equals(dragging[0].name())).findFirst().orElse(null);
        });
        canvas.setOnMouseReleased(e -> dragging[0] = null);

        canvas.setOnContextMenuRequested(e -> {
            var menu = new ContextMenu();
            var iso  = findIsochromat(e.getX(), e.getY());
            if (iso != null) {
                var del  = new MenuItem("Delete '" + iso.name() + "'");
                var tog  = new MenuItem(iso.visible() ? "Hide" : "Show");
                del.setOnAction(ae -> appState.isochromats.removeIsochromat(iso));
                tog.setOnAction(ae -> appState.isochromats.toggleVisibility(iso));
                menu.getItems().addAll(del, tog, new SeparatorMenuItem());
            }
            var rz   = screenToRZ(e.getX(), e.getY());
            var add  = new MenuItem(String.format("Add point (r=%.1f, z=%.1f)", rz[0], rz[1]));
            add.setOnAction(ae -> appState.isochromats.addIsochromat(rz[0], rz[1],
                String.format("(%.0f,%.0f)", rz[0], rz[1])));
            var reset = new MenuItem("Reset to defaults");
            reset.setOnAction(ae -> appState.isochromats.resetToDefaults());
            var clear = new MenuItem("Clear all points");
            clear.setOnAction(ae -> appState.isochromats.clear());
            menu.getItems().addAll(add, new SeparatorMenuItem(), reset, clear);
            menu.show(canvas, e.getScreenX(), e.getScreenY());
        });

        canvas.setOnScroll(e -> {
            double h = appState.crossSection.halfHeight.get();
            h *= (e.getDeltaY() > 0 ? 0.85 : 1.18);
            appState.crossSection.halfHeight.set(MathUtil.clamp(h, 5, 300));
        });
    }

    @Override
    protected void paint(GraphicsContext g, double w, double h) {
        g.setFill(BG); g.fillRect(0, 0, w, h);
        var data  = appState.document.blochData.get();
        var pulse = appState.document.currentPulse.get();
        if (data == null || pulse == null) return;

        var f    = data.field();
        double rMax = f.rMm[f.rMm.length - 1];
        double xsH  = appState.crossSection.halfHeight.get();
        double tC   = appState.viewport.tC.get();
        var    mode = appState.crossSection.shadeMode.get();
        boolean shading = appState.crossSection.shadingEnabled.get();

        double pad_l = 28, pad_r = 8, pad_t = 8, pad_b = 20;
        double pW = w - pad_l - pad_r;
        double pH = h - pad_t - pad_b;

        // Shading (only if enabled)
        if (shading) {
            var zSamples = buildZSamples(f.zMm[f.zMm.length - 1]);
            record CellSample(double mx, double my, double mp, double phaseDeg, double rMm, double zMm) {}
            var cells    = new ArrayList<CellSample>(RADIAL_SAMPLES * zSamples.size());
            double sumMx = 0, sumMy = 0;
            for (int ir = 0; ir < RADIAL_SAMPLES; ir++) {
                double r = (double) ir / (RADIAL_SAMPLES - 1) * rMax;
                for (double z : zSamples) {
                    var st = BlochSimulator.simulateTo(data, r, z, pulse, tC);
                    double mp  = st.mPerp();
                    sumMx += st.mx(); sumMy += st.my();
                    cells.add(new CellSample(st.mx(), st.my(), mp, st.phaseDeg(), r, z));
                }
            }
            double sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
            double ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
            double uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;

            g.save();
            g.beginPath(); g.rect(pad_l, pad_t, pW, pH); g.clip();
            int nZ = zSamples.size();
            for (int ir = 0; ir < RADIAL_SAMPLES; ir++) {
                double r0 = (double) ir / (RADIAL_SAMPLES - 1) * rMax;
                double r1 = ir < RADIAL_SAMPLES - 1 ? (double) (ir + 1) / (RADIAL_SAMPLES - 1) * rMax : rMax;
                double x0 = pad_l + (r0 / rMax) * pW;
                double x1 = ir < RADIAL_SAMPLES - 1 ? pad_l + (r1 / rMax) * pW : pad_l + pW;
                for (int iz = 0; iz < nZ; iz++) {
                    var cell  = cells.get(ir * nZ + iz);
                    double z0 = zSamples.get(iz);
                    double zPrev = iz > 0 ? 0.5 * (zSamples.get(iz - 1) + z0) : z0;
                    double zNext = iz < nZ - 1 ? 0.5 * (z0 + zSamples.get(iz + 1)) : z0;
                    double zTop  = iz < nZ - 1 ? zNext : z0 + (z0 - zPrev);
                    double zBot  = iz > 0      ? zPrev : z0 - (zNext - z0);
                    if (zTop < -xsH || zBot > xsH) continue;
                    double brightness = cell.mp();
                    if (mode == ShadeMode.SIGNAL)
                        brightness = Math.max(0, cell.mx() * ux + cell.my() * uy);
                    double y0 = pad_t + pH * (1 - (Math.min(zTop, xsH)  - (-xsH)) / (2 * xsH));
                    double y1 = pad_t + pH * (1 - (Math.max(zBot, -xsH) - (-xsH)) / (2 * xsH));
                    g.setFill(ColorUtil.hue2color(cell.phaseDeg(), MathUtil.clamp(brightness, 0, 1)));
                    g.fillRect(Math.min(x0, x1), Math.min(y0, y1),
                               Math.abs(x1 - x0) + 1, Math.abs(y1 - y0) + 1);
                }
            }
            g.restore();
        }

        // Slice boundary lines
        double sh = (f.sliceHalf != null ? f.sliceHalf : 0.005) * 1e3;
        double yTop = pad_t + pH * (1 - (sh  - (-xsH)) / (2 * xsH));
        double yBot = pad_t + pH * (1 - (-sh - (-xsH)) / (2 * xsH));
        g.setStroke(Color.web("#2e7d32")); g.setGlobalAlpha(0.5); g.setLineWidth(1);
        g.setLineDashes(4, 3);
        g.strokeLine(pad_l, yTop, pad_l + pW, yTop);
        g.strokeLine(pad_l, yBot, pad_l + pW, yBot);
        g.setLineDashes();
        g.setFill(Color.web("#2e7d32")); g.setGlobalAlpha(0.5);
        g.setFont(UI_BOLD_7);
        g.fillText("slice", pad_l + 2, (yTop + yBot) / 2 + 3);
        g.setGlobalAlpha(1);

        // Axes
        g.setStroke(Color.color(0, 0, 0, 0.2)); g.setLineWidth(0.5);
        g.beginPath(); g.moveTo(pad_l, pad_t); g.lineTo(pad_l, pad_t + pH);
        g.lineTo(pad_l + pW, pad_t + pH); g.stroke();

        // Z ticks
        g.setFill(TX2); g.setFont(UI_7); g.setTextAlign(TextAlignment.RIGHT); g.setGlobalAlpha(0.8);
        int zTickStep = xsH > 50 ? 50 : xsH > 20 ? 10 : xsH > 8 ? 5 : 2;
        for (double z = -Math.floor(xsH / zTickStep) * zTickStep; z <= xsH; z += zTickStep) {
            double y = pad_t + pH * (1 - (z - (-xsH)) / (2 * xsH));
            if (y < pad_t + 2 || y > pad_t + pH - 2) continue;
            g.fillText(String.valueOf((int) z), pad_l - 3, y + 3);
            g.setStroke(Color.color(0, 0, 0, 0.06)); g.setLineWidth(0.3);
            g.strokeLine(pad_l, y, pad_l + pW, y);
        }

        // R ticks
        g.setTextAlign(TextAlignment.CENTER);
        int rTickStep = rMax > 60 ? 20 : rMax > 30 ? 10 : 5;
        for (double r = 0; r <= rMax; r += rTickStep) {
            double px = pad_l + (r / rMax) * pW;
            if (px < pad_l + 2 || px > pad_l + pW - 2) continue;
            g.fillText(String.valueOf((int) r), px, pad_t + pH + 11);
            g.setStroke(Color.color(0, 0, 0, 0.06)); g.setLineWidth(0.3);
            g.strokeLine(px, pad_t, px, pad_t + pH);
        }

        // Axis labels
        g.setFill(TX2); g.setFont(UI_BOLD_7); g.setGlobalAlpha(0.6);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("r [mm]", pad_l + pW / 2, h - 2);
        g.save(); g.translate(8, pad_t + pH / 2); g.rotate(-90);
        g.fillText("z [mm]", 0, 0); g.restore();
        g.setGlobalAlpha(1); g.setTextAlign(TextAlignment.LEFT);

        // Isochromat dots
        for (var iso : appState.isochromats.isochromats) {
            double px = pad_l + (iso.r() / rMax) * pW;
            double py = pad_t + pH * (1 - (iso.z() - (-xsH)) / (2 * xsH));
            if (py < pad_t - 5 || py > pad_t + pH + 5 || px < pad_l - 5 || px > pad_l + pW + 5) continue;
            g.setFill(iso.colour()); g.setGlobalAlpha(iso.visible() ? 0.9 : 0.2);
            g.fillOval(px - 4, py - 4, 8, 8);
            g.setStroke(Color.color(0, 0, 0, 0.4)); g.setLineWidth(1);
            g.setGlobalAlpha(iso.visible() ? 0.7 : 0.2);
            g.strokeOval(px - 4, py - 4, 8, 8);
            // Label
            g.setGlobalAlpha(iso.visible() ? 0.7 : 0.2);
            g.setFill(TX); g.setFont(UI_7);
            g.fillText(iso.name(), px + 6, py + 3);
            g.setGlobalAlpha(1);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private double[] screenToRZ(double mouseX, double mouseY) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double pad_l = 28, pad_r = 8, pad_t = 8, pad_b = 20;
        double pW = w - pad_l - pad_r, pH = h - pad_t - pad_b;
        var f = appState.document.blochData.get().field();
        double rMax = f.rMm[f.rMm.length - 1];
        double xsH  = appState.crossSection.halfHeight.get();
        double r = (mouseX - pad_l) / pW * rMax;
        double z = (1 - (mouseY - pad_t) / pH) * 2 * xsH - xsH;
        return new double[]{Math.max(0, r), z};
    }

    private Isochromat findIsochromat(double mouseX, double mouseY) {
        var data = appState.document.blochData.get();
        if (data == null) return null;
        var f    = data.field();
        double rMax = f.rMm[f.rMm.length - 1];
        double w = canvas.getWidth(), h = canvas.getHeight();
        double pad_l = 28, pad_r = 8, pad_t = 8, pad_b = 20;
        double pW = w - pad_l - pad_r, pH = h - pad_t - pad_b;
        double xsH = appState.crossSection.halfHeight.get();
        for (var iso : appState.isochromats.isochromats) {
            double px = pad_l + (iso.r() / rMax) * pW;
            double py = pad_t + pH * (1 - (iso.z() - (-xsH)) / (2 * xsH));
            if (Math.hypot(mouseX - px, mouseY - py) < 8) return iso;
        }
        return null;
    }

    private static ArrayList<Double> buildZSamples(double zMax) {
        var set = new HashSet<Double>();
        double inner = Math.min(8, zMax);
        double mid   = Math.min(20, zMax);
        for (double z = -inner; z <= inner + 1e-6; z += INNER_Z_STEP) set.add(round3(z));
        for (double z = -mid;   z <= mid   + 1e-6; z += MID_Z_STEP)   set.add(round3(z));
        if (zMax > mid) {
            double step = (zMax - mid) / OUTER_BANDS;
            for (int i = 0; i <= OUTER_BANDS; i++) {
                double z = mid + i * step;
                set.add(round3(z)); set.add(round3(-z));
            }
        }
        set.add(-zMax); set.add(zMax);
        var list = new ArrayList<>(set);
        list.sort(Double::compareTo);
        return list;
    }

    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
