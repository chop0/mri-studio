package ax.xz.mri.state;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.Isochromat;
import ax.xz.mri.service.simulation.BlochSimulator;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

import java.util.List;

/** Manages the observable list of isochromats and triggers re-simulation when the pulse changes. */
public class IsochromatState {
    public final ObservableList<Isochromat> isochromats = FXCollections.observableArrayList();

    private BlochData            data;
    private List<PulseSegment>   pulse;
    private int                  colourIndex = 0;

    public void setContext(BlochData data, List<PulseSegment> pulse) {
        this.data  = data;
        this.pulse = pulse;
    }

    /** Replace the entire list with defaults from the loaded BlochData. */
    public void resetToDefaults() {
        isochromats.clear();
        colourIndex = 0;
        if (data == null) return;
        var defs = data.iso();
        if (defs == null || defs.isEmpty()) {
            // Fallback: one isochromat at centre of slice
            addIsochromat(0, 0, "centre");
            return;
        }
        for (var def : defs) {
            Color colour;
            try { colour = Color.web(def.colour()); }
            catch (Exception e) { colour = nextColour(); }
            var iso = new Isochromat(0, 0, colour, def.inSlice(), def.name(), null);
            isochromats.add(resimulate(iso));
        }
    }

    public void addIsochromat(double r, double z, String name) {
        var iso = new Isochromat(r, z, nextColour(), true, name, null);
        isochromats.add(resimulate(iso));
    }

    public void removeIsochromat(Isochromat iso) {
        isochromats.remove(iso);
    }

    public void toggleVisibility(Isochromat iso) {
        int i = isochromats.indexOf(iso);
        if (i >= 0) isochromats.set(i, iso.withVisible(!iso.visible()));
    }

    public void moveIsochromat(Isochromat iso, double newR, double newZ) {
        int i = isochromats.indexOf(iso);
        if (i >= 0) {
            var moved = iso.withPosition(newR, newZ);
            isochromats.set(i, resimulate(moved));
        }
    }

    /** Re-simulate every isochromat with the current pulse. */
    public void resimulateAll() {
        for (int i = 0; i < isochromats.size(); i++)
            isochromats.set(i, resimulate(isochromats.get(i)));
    }

    public void clear() { isochromats.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Isochromat resimulate(Isochromat iso) {
        if (data == null || pulse == null) return iso;
        var traj = BlochSimulator.simulate(data, iso.r(), iso.z(), pulse);
        return traj != null ? iso.withTrajectory(traj) : iso;
    }

    private Color nextColour() {
        var colours = StudioTheme.ISOCHROMAT_COLOURS;
        return colours[colourIndex++ % colours.length];
    }
}
