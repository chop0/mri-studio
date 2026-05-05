module ax.xz.mri {
    requires javafx.controls;
    requires javafx.graphics;
    requires com.fasterxml.jackson.databind;
    requires bento.fx;
    requires org.codehaus.commons.compiler;
    requires org.codehaus.janino;
    requires java.desktop;
    requires java.net.http;
    requires com.google.protobuf;

    opens ax.xz.mri.model.field    to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.sequence to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.simulation to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.scenario to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.circuit to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.circuit.compile to com.fasterxml.jackson.databind;
    opens ax.xz.mri.project to com.fasterxml.jackson.databind;
    opens ax.xz.mri.ui.workbench to com.fasterxml.jackson.databind;
    opens ax.xz.mri.ui.workbench.layout to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware.builtin to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware.builtin.redpitaya to com.fasterxml.jackson.databind;
    opens ax.xz.mri.state to com.fasterxml.jackson.databind;
//    opens ax.xz.mri.model.compile to com.fasterxml.jackson.databind;

    exports ax.xz.mri;
    exports ax.xz.mri.model.simulation;
    exports ax.xz.mri.optimisation;
    exports ax.xz.mri.hardware;

    uses ax.xz.mri.hardware.HardwarePlugin;
    provides ax.xz.mri.hardware.HardwarePlugin
        with ax.xz.mri.hardware.builtin.MockHardwarePlugin,
             ax.xz.mri.hardware.builtin.redpitaya.RedPitayaPlugin;
}
