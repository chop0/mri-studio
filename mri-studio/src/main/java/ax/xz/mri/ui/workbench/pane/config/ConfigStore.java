package ax.xz.mri.ui.workbench.pane.config;

import ax.xz.mri.model.simulation.DrivePath;
import ax.xz.mri.model.simulation.ReceiveCoil;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.TransmitCoil;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.binding.IntegerBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/**
 * Observable store over a {@link SimulationConfig} record. Exposes one JavaFX
 * property per field plus derived bindings. Maintains two-way sync between
 * the record and the properties so the UI can bind plain bidirectionally.
 */
public final class ConfigStore {
    public final ObjectProperty<SimulationConfig> config = new SimpleObjectProperty<>();

    public final DoubleProperty  t1Ms             = new SimpleDoubleProperty();
    public final DoubleProperty  t2Ms             = new SimpleDoubleProperty();
    public final DoubleProperty  gamma            = new SimpleDoubleProperty();
    public final DoubleProperty  sliceHalfMm      = new SimpleDoubleProperty();
    public final DoubleProperty  fovZMm           = new SimpleDoubleProperty();
    public final DoubleProperty  fovRMm           = new SimpleDoubleProperty();
    public final IntegerProperty nZ               = new SimpleIntegerProperty();
    public final IntegerProperty nR               = new SimpleIntegerProperty();
    public final DoubleProperty  referenceB0Tesla = new SimpleDoubleProperty();
    public final DoubleProperty  dtSeconds        = new SimpleDoubleProperty();
    public final ObservableList<TransmitCoil> transmitCoils = FXCollections.observableArrayList();
    public final ObservableList<DrivePath> drivePaths = FXCollections.observableArrayList();
    public final ObservableList<ReceiveCoil> receiveCoils = FXCollections.observableArrayList();

    public final DoubleBinding  larmorHz;
    public final DoubleBinding  nyquistHz;
    public final IntegerBinding totalChannels;

    private boolean syncing;

    public ConfigStore(SimulationConfig initial) {
        larmorHz = Bindings.createDoubleBinding(
            () -> gamma.get() * referenceB0Tesla.get() / (2 * Math.PI),
            gamma, referenceB0Tesla);
        nyquistHz = Bindings.createDoubleBinding(
            () -> dtSeconds.get() > 0 ? 1.0 / (2 * dtSeconds.get()) : Double.POSITIVE_INFINITY,
            dtSeconds);
        totalChannels = Bindings.createIntegerBinding(() -> {
            int sum = 0;
            for (var p : drivePaths) sum += p.channelCount();
            return sum;
        }, drivePaths);

        writeFrom(initial);
        config.set(initial);

        t1Ms.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withT1Ms(n.doubleValue())));
        t2Ms.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withT2Ms(n.doubleValue())));
        gamma.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withGamma(n.doubleValue())));
        sliceHalfMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withSliceHalfMm(n.doubleValue())));
        fovZMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withFovZMm(n.doubleValue())));
        fovRMm.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withFovRMm(n.doubleValue())));
        nZ.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withNZ(n.intValue())));
        nR.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withNR(n.intValue())));
        referenceB0Tesla.addListener((obs, o, n) -> rebuildFromProperties(c -> c.withReferenceB0Tesla(n.doubleValue())));
        dtSeconds.addListener((obs, o, n) -> rebuildFromProperties(c -> {
            double v = n.doubleValue();
            return v > 0 ? c.withDtSeconds(v) : c;
        }));
        transmitCoils.addListener((javafx.collections.ListChangeListener<TransmitCoil>) ch ->
            rebuildFromProperties(c -> c.withTransmitCoils(List.copyOf(transmitCoils))));
        drivePaths.addListener((javafx.collections.ListChangeListener<DrivePath>) ch ->
            rebuildFromProperties(c -> c.withDrivePaths(List.copyOf(drivePaths))));
        receiveCoils.addListener((javafx.collections.ListChangeListener<ReceiveCoil>) ch ->
            rebuildFromProperties(c -> c.withReceiveCoils(List.copyOf(receiveCoils))));

        config.addListener((obs, oldC, newC) -> {
            if (syncing || newC == null) return;
            syncing = true;
            try { writeFrom(newC); } finally { syncing = false; }
        });
    }

    public void setConfig(SimulationConfig c) {
        if (c == null || c.equals(config.get())) return;
        config.set(c);
    }

    public SimulationConfig getConfig() {
        return config.get();
    }

    private void rebuildFromProperties(java.util.function.UnaryOperator<SimulationConfig> delta) {
        if (syncing) return;
        var current = config.get();
        if (current == null) return;
        var next = delta.apply(current);
        if (next == null || next.equals(current)) return;
        syncing = true;
        try { config.set(next); } finally { syncing = false; }
    }

    private void writeFrom(SimulationConfig c) {
        if (c == null) return;
        t1Ms.set(c.t1Ms());
        t2Ms.set(c.t2Ms());
        gamma.set(c.gamma());
        sliceHalfMm.set(c.sliceHalfMm());
        fovZMm.set(c.fovZMm());
        fovRMm.set(c.fovRMm());
        nZ.set(c.nZ());
        nR.set(c.nR());
        referenceB0Tesla.set(c.referenceB0Tesla());
        dtSeconds.set(c.dtSeconds());
        if (!listEquals(transmitCoils, c.transmitCoils())) transmitCoils.setAll(c.transmitCoils());
        if (!listEquals(drivePaths, c.drivePaths())) drivePaths.setAll(c.drivePaths());
        if (!listEquals(receiveCoils, c.receiveCoils())) receiveCoils.setAll(c.receiveCoils());
    }

    private static <T> boolean listEquals(List<T> a, List<T> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }
}
