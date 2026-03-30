package ax.xz.mri.ui.pane;

import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.canvas.Projection;
import ax.xz.mri.ui.framework.CanvasPane;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.paint.Color;

import static ax.xz.mri.ui.canvas.Projection.project;
import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * Bloch sphere visualisation with drag-to-rotate and scroll-to-zoom.
 * Port of {@code drawSphere()} from draw/sphere.ts.
 */
public class SpherePane extends CanvasPane {

    public SpherePane(AppState s) {
        super(s);
        installMouseHandlers();
        onAttached();
    }

    @Override public String getPaneId()    { return "sphere"; }
    @Override public String getPaneTitle() { return "Bloch Sphere"; }

    // ── Header toolbar ───────────────────────────────────────────────────────

    @Override
    protected Node[] headerControls() {
        Button front = new Button("Front");
        front.setOnAction(e -> setCameraPreset(0, 0));

        Button top = new Button("Top");
        top.setOnAction(e -> setCameraPreset(0, Math.PI / 2));

        Button iso = new Button("ISO");
        iso.setOnAction(e -> setCameraPreset(0.6, 0.5));

        Button reset = new Button("Reset");
        reset.setOnAction(e -> {
            appState.camera.theta.set(0.6);
            appState.camera.phi.set(0.3);
            appState.camera.zoom.set(1.0);
        });

        for (Button b : new Button[]{ front, top, iso, reset }) {
            b.setFont(UI_8);
            b.setPadding(new javafx.geometry.Insets(1, 5, 1, 5));
        }

        return new Node[]{ front, top, iso, reset };
    }

    private void setCameraPreset(double theta, double phi) {
        appState.camera.theta.set(theta);
        appState.camera.phi.set(phi);
    }

    // ── Redraw triggers ──────────────────────────────────────────────────────

    @Override
    protected Observable[] getRedrawTriggers() {
        return new Observable[]{
            appState.isochromats.isochromats,
            appState.camera.theta,
            appState.camera.phi,
            appState.camera.zoom,
            appState.viewport.tS,
            appState.viewport.tE,
            appState.viewport.tC,
            appState.crossSection.showMpProj,
        };
    }

    // ── Mouse handlers ───────────────────────────────────────────────────────

    @Override
    protected void installMouseHandlers() {
        final double[] drag = new double[2];
        canvas.setOnMousePressed(e  -> { drag[0] = e.getX(); drag[1] = e.getY(); });
        canvas.setOnMouseDragged(e  -> {
            appState.camera.addTheta((e.getX() - drag[0]) * 0.008);
            appState.camera.addPhi  ((e.getY() - drag[1]) * 0.008);
            drag[0] = e.getX(); drag[1] = e.getY();
        });
        canvas.setOnScroll(e -> appState.camera.addZoom(e.getDeltaY() > 0 ? 1.1 : 0.91));

        // Status bar: show camera state and cursor time on mouse move
        canvas.setOnMouseMoved(e -> {
            double theta = appState.camera.theta.get();
            double phi   = appState.camera.phi.get();
            double zoom  = appState.camera.zoom.get();
            double tC    = appState.viewport.tC.get();
            setStatus(String.format(
                "\u03b8=%.1f\u00b0 \u03c6=%.1f\u00b0 zoom=%.0f%% | cursor: %.1f \u00b5s",
                Math.toDegrees(theta), Math.toDegrees(phi), zoom * 100, tC
            ));
        });

        // Right-click context menu
        canvas.setOnContextMenuRequested(e -> {
            ContextMenu menu = buildContextMenu();
            showContextMenu(menu, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // ── Context menu ─────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu() {
        MenuItem resetItem = new MenuItem("Reset View");
        resetItem.setOnAction(e -> {
            appState.camera.theta.set(0.6);
            appState.camera.phi.set(0.3);
            appState.camera.zoom.set(1.0);
        });

        MenuItem frontItem = new MenuItem("Front View");
        frontItem.setOnAction(e -> setCameraPreset(0, 0));

        MenuItem topItem = new MenuItem("Top View");
        topItem.setOnAction(e -> setCameraPreset(0, Math.PI / 2));

        MenuItem isoItem = new MenuItem("ISO View");
        isoItem.setOnAction(e -> setCameraPreset(0.6, 0.5));

        CheckMenuItem showMpItem = new CheckMenuItem("Show |M\u22a5| Projection");
        showMpItem.setSelected(appState.crossSection.showMpProj.get());
        showMpItem.setOnAction(e -> appState.crossSection.showMpProj.set(showMpItem.isSelected()));

        return new ContextMenu(
            resetItem,
            new SeparatorMenuItem(),
            frontItem,
            topItem,
            isoItem,
            new SeparatorMenuItem(),
            showMpItem
        );
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    protected void paint(GraphicsContext g, double w, double h) {
        double cx    = w / 2, cy = h / 2;
        double theta = appState.camera.theta.get();
        double phi   = appState.camera.phi.get();
        double scale = Math.min(w, h) * 0.37 * appState.camera.zoom.get();

        g.setFill(StudioTheme.BG);
        g.fillRect(0, 0, w, h);

        // ── Wireframe rings ──────────────────────────────────────────────────
        drawRing(g, theta, phi, scale, cx, cy, 'e');
        drawRing(g, theta, phi, scale, cx, cy, 'x');
        drawRing(g, theta, phi, scale, cx, cy, 'y');

        // ── Axis arrows ──────────────────────────────────────────────────────
        double[][] axes = {
            {1.15, 0, 0},
            {0, 1.15, 0},
            {0, 0, 1.15},
        };
        String[] labels = { "Mx", "My", "Mz" };
        Color[]  colors = { Color.web("#d32f2f"), Color.web("#2e7d32"), Color.web("#1565c0") };

        double[] p0 = project(0, 0, 0, theta, phi, scale, cx, cy);
        for (int i = 0; i < 3; i++) {
            var p1    = project(axes[i][0], axes[i][1], axes[i][2], theta, phi, scale, cx, cy);
            double depth = (1 + p1[2]) / 2.0;
            g.setStroke(colors[i]);
            g.setLineWidth(0.5 + 0.5 * depth);
            g.setGlobalAlpha(0.25 + 0.55 * depth);
            g.strokeLine(p0[0], p0[1], p1[0], p1[1]);
            g.setFill(colors[i]);
            g.setFont(javafx.scene.text.Font.font(StudioTheme.UI_9.getFamily(),
                javafx.scene.text.FontWeight.SEMI_BOLD, 10 + depth));
            g.setGlobalAlpha(0.4 + 0.5 * depth);
            g.fillText(labels[i], p1[0] + 4, p1[1] - 3);
            g.setGlobalAlpha(1);
        }

        // ── Isochromat trajectories ──────────────────────────────────────────
        double tS = appState.viewport.tS.get();
        double tE = appState.viewport.tE.get();
        double tC = appState.viewport.tC.get();
        boolean showMp = appState.crossSection.showMpProj.get();

        for (var iso : appState.isochromats.isochromats) {
            if (!iso.visible() || iso.trajectory() == null) continue;
            var traj = iso.trajectory();
            int n    = traj.pointCount();

            int wStart = -1, wEnd = -1;
            for (int i = 0; i < n; i++) {
                double t = traj.tAt(i);
                if (t >= tS && t <= tE) {
                    if (wStart < 0) wStart = i;
                    wEnd = i;
                }
            }
            if (wStart < 0 || wEnd <= wStart) continue;

            int ss = wStart;
            for (int i = wStart + 1; i <= wEnd + 1; i++) {
                boolean segEnd = (i > wEnd || traj.isRfAt(i) != traj.isRfAt(i - 1));
                if (!segEnd) continue;
                if (i - ss >= 2) {
                    boolean isPulse = traj.isRfAt(ss);
                    double  sumDepth = 0;
                    for (int j = ss; j < i; j++) {
                        var p = project(traj.mxAt(j), traj.myAt(j), traj.mzAt(j), theta, phi, scale, cx, cy);
                        sumDepth += p[2];
                    }
                    double fade = 0.3 + 0.7 * (1 + sumDepth / (i - ss)) / 2.0;
                    g.setStroke(iso.colour());
                    g.setLineWidth(isPulse ? 1.8 : 1.0);
                    g.setGlobalAlpha((isPulse ? 0.8 : 0.1) * fade);
                    g.beginPath();
                    for (int j = ss; j < i; j++) {
                        var p = project(traj.mxAt(j), traj.myAt(j), traj.mzAt(j), theta, phi, scale, cx, cy);
                        if (j == ss) g.moveTo(p[0], p[1]); else g.lineTo(p[0], p[1]);
                    }
                    g.stroke();
                    g.setGlobalAlpha(1);
                }
                ss = i;
            }

            // Cursor dot
            var st = traj.interpolateAt(tC);
            if (st == null) continue;
            double mx = st.mx(), my = st.my(), mz = st.mz();
            double mag   = Math.sqrt(mx * mx + my * my + mz * mz);
            double mPerp = Math.sqrt(mx * mx + my * my);
            double ux    = mag > 1e-6 ? mx / mag : 0;
            double uy    = mag > 1e-6 ? my / mag : 0;
            double uz    = mag > 1e-6 ? mz / mag : 0;
            var    pS    = project(ux, uy, uz, theta, phi, scale, cx, cy);
            double fade  = 0.5 + 0.5 * (1 + pS[2]) / 2.0;
            double r     = 3 + 2 * fade;

            g.setFill(iso.colour());
            g.setGlobalAlpha(fade);
            g.fillOval(pS[0] - r, pS[1] - r, 2 * r, 2 * r);
            g.setStroke(Color.color(1, 1, 1, 0.6));
            g.setLineWidth(1);
            g.setGlobalAlpha(0.7 * fade);
            g.strokeOval(pS[0] - r, pS[1] - r, 2 * r, 2 * r);
            g.setGlobalAlpha(1);

            // |M⊥| projection (optional)
            if (showMp && mPerp > 0.01) {
                var pA = project(mx, my, 0, theta, phi, scale, cx, cy);
                g.setLineDashes(3, 3);
                g.setStroke(iso.colour()); g.setGlobalAlpha(0.35); g.setLineWidth(1);
                g.strokeLine(pS[0], pS[1], pA[0], pA[1]);
                g.setLineDashes();
                g.setStroke(iso.colour()); g.setLineWidth(1.5);
                g.setGlobalAlpha(0.5 + 0.3 * mPerp);
                double pr = 2 + 4 * mPerp;
                g.strokeOval(pA[0] - pr, pA[1] - pr, 2 * pr, 2 * pr);
                g.setFill(iso.colour());
                g.setFont(StudioTheme.UI_8);
                g.setGlobalAlpha(0.55);
                g.fillText(String.format("|M\u22a5|=%.2f", mPerp), pA[0] + 8, pA[1] + 3);
                g.setGlobalAlpha(1);
            }
        }
    }

    // ── Ring drawing ──────────────────────────────────────────────────────────

    private void drawRing(GraphicsContext g, double theta, double phi,
                          double scale, double cx, double cy, char plane) {
        for (int pass = 0; pass < 2; pass++) {
            g.beginPath();
            boolean started = false;
            for (int i = 0; i <= 80; i++) {
                double a = i / 80.0 * Math.PI * 2;
                double[] p = switch (plane) {
                    case 'e' -> project(Math.cos(a), Math.sin(a), 0, theta, phi, scale, cx, cy);
                    case 'x' -> project(Math.cos(a), 0, Math.sin(a), theta, phi, scale, cx, cy);
                    default  -> project(0, Math.cos(a), Math.sin(a), theta, phi, scale, cx, cy);
                };
                boolean inFront = p[2] > 0;
                if ((pass == 0 && !inFront) || (pass == 1 && inFront)) {
                    if (!started) { g.moveTo(p[0], p[1]); started = true; }
                    else           g.lineTo(p[0], p[1]);
                } else {
                    started = false;
                }
            }
            g.setStroke(pass == 1
                ? Color.color(0, 0, 0, 0.18)
                : Color.color(0, 0, 0, 0.06));
            g.setLineWidth(pass == 1 ? 0.6 : 0.4);
            g.stroke();
        }
    }
}
