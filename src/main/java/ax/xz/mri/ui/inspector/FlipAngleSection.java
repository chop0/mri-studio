package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.pane.inspector.ClipRotationAnalysis;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Per-point flip-angle readout. For each visible isochromat point with a
 * trajectory, renders one row showing the rotation the selected clip applied
 * to that point's magnetisation: angle in degrees + axis (compact glyphs
 * for ±x̂/±ŷ/±ẑ, otherwise an explicit triple).
 *
 * <p>This restores functionality the pre-refactor inspector had — the user
 * called it out as critical for diagnosing pulse-design problems where
 * different points see different effective B₁ magnitudes.
 */
final class FlipAngleSection {
    private FlipAngleSection() {}

    static Node build(StudioSession session, SignalClip clip) {
        var box = new VBox(4);
        var title = new Label("Flip angle by point of interest");
        title.getStyleClass().add("inspector-section-title");
        box.getChildren().add(title);

        Runnable refresh = () -> {
            box.getChildren().setAll(title);
            var visible = session.points.entries.stream()
                .filter(IsochromatEntry::visible)
                .toList();
            if (visible.isEmpty()) {
                box.getChildren().add(hint("No points of interest. Click the sphere to add one."));
                return;
            }
            boolean anyTrajectory = visible.stream().anyMatch(p -> p.trajectory() != null);
            if (!anyTrajectory) {
                box.getChildren().add(hint("Run simulation to see rotation per point."));
                return;
            }
            for (var p : visible) box.getChildren().add(rowFor(p, clip));
        };
        refresh.run();
        // Repopulate when the points list mutates or trajectories arrive.
        session.points.entries.addListener(
            (javafx.collections.ListChangeListener<IsochromatEntry>) c -> refresh.run());
        return box;
    }

    private static Node rowFor(IsochromatEntry poi, SignalClip clip) {
        var p = poi.position();
        var name = new Label((poi.name() == null || poi.name().isBlank())
            ? String.format("(%.2f, %.2f, %.2f) mm", p.x() * 1e3, p.y() * 1e3, p.z() * 1e3)
            : poi.name());
        var coords = new Label(String.format("x=%.2f, y=%.2f, z=%.2f mm",
            p.x() * 1e3, p.y() * 1e3, p.z() * 1e3));
        coords.getStyleClass().add("inspector-hint");

        var analysis = ClipRotationAnalysis.ofClip(poi.trajectory(), clip.startTime(), clip.endTime());
        var detail = new Label(analysis == null
            ? "(no trajectory)"
            : String.format("%.1f° about %s",
                analysis.angleDegrees(),
                formatAxis(analysis.axisX(), analysis.axisY(), analysis.axisZ())));
        detail.getStyleClass().add("inspector-derived-value");

        var top = new HBox(6, name, coords);
        top.setAlignment(Pos.CENTER_LEFT);
        return new VBox(0, top, detail);
    }

    /** Compact axis: pure ±x̂/±ŷ/±ẑ render as a glyph; otherwise full triple. */
    private static String formatAxis(double nx, double ny, double nz) {
        double tol = 0.02;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax > 1 - tol && ay < tol && az < tol) return nx > 0 ? "+x̂" : "−x̂";
        if (ay > 1 - tol && ax < tol && az < tol) return ny > 0 ? "+ŷ" : "−ŷ";
        if (az > 1 - tol && ax < tol && ay < tol) return nz > 0 ? "+ẑ" : "−ẑ";
        return String.format("(%+.2f, %+.2f, %+.2f)", nx, ny, nz);
    }

    private static Label hint(String text) {
        var l = new Label(text);
        l.getStyleClass().add("inspector-hint");
        l.setWrapText(true);
        return l;
    }
}
