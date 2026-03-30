module ax.xz.mri {
    requires javafx.controls;
    requires javafx.graphics;
    requires com.fasterxml.jackson.databind;

    opens ax.xz.mri.model.field    to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.sequence to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.simulation to com.fasterxml.jackson.databind;
    opens ax.xz.mri.model.scenario to com.fasterxml.jackson.databind;

    exports ax.xz.mri;
    exports ax.xz.mri.ui.aerofx.skin to javafx.controls;
}
