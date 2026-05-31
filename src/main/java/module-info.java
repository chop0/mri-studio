module ax.xz.mri {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.swing;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jdk8;
    requires bento.fx;
    requires jdk.compiler;            // javax.tools.JavaCompiler for DSL script compilation
    requires java.compiler;           // public javac SPI
    requires java.desktop;
    requires java.net.http;
    requires com.google.protobuf;
    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.core;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.carbonicons;

    opens ax.xz.mri.model.field    to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.sequence to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.simulation to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.scenario to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.circuit to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.circuit.compile to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.nv to com.fasterxml.jackson.databind;
    opens ax.xz.mri.project to com.fasterxml.jackson.databind;
    opens ax.xz.mri.ui.workbench to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware.builtin to com.fasterxml.jackson.databind;
    opens ax.xz.mri.hardware.builtin.redpitaya to com.fasterxml.jackson.databind;
    opens ax.xz.mri.state to com.fasterxml.jackson.databind;
//    opens ax.xz.mri.model.compile to com.fasterxml.jackson.databind;

    exports ax.xz.mri;
    exports ax.xz.mri.dsl;
    exports ax.xz.mri.dsl.viz;                  // Visualisation kinds — procedures call ctx.show(...) with these
    exports ax.xz.mri.model.simulation;
    exports ax.xz.mri.model.substance;
    exports ax.xz.mri.model.substance.output;
    exports ax.xz.mri.model.probe;
    exports ax.xz.mri.model.field;
    exports ax.xz.mri.model.nv;
    exports ax.xz.mri.model.sequence;            // Segment, PulseSegment, PulseStep — procedure scripts that hit the ObservationSource path
    exports ax.xz.mri.optimisation;
    exports ax.xz.mri.hardware;
    exports ax.xz.mri.service.simulation.compiled;
    exports ax.xz.mri.service.procedure;         // ObservationSource — procedure scripts read it off the context
    exports ax.xz.mri.service.circuit;           // CompiledCircuit / CompiledSource — procedure scripts walk the source list to find channel offsets

    uses ax.xz.mri.hardware.HardwarePlugin;
    provides ax.xz.mri.hardware.HardwarePlugin
        with ax.xz.mri.hardware.builtin.MockHardwarePlugin,
             ax.xz.mri.hardware.builtin.redpitaya.RedPitayaPlugin;
}
