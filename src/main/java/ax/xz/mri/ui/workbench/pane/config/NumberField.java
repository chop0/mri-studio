package ax.xz.mri.ui.workbench.pane.config;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * A modern number input that replaces {@link javafx.scene.control.Spinner} in the
 * configuration editor.
 *
 * <p>Features:
 * <ul>
 *   <li>Accepts plain decimals, scientific notation ({@code 1e-6}, {@code 2.5e3}),
 *       and common unit-agnostic input. The parser is tolerant of surrounding
 *       whitespace and a leading {@code +}.</li>
 *   <li>Arrow-key stepping: Up / Down adjusts by the step value;
 *       Shift+Arrow uses a 10× step; Alt+Arrow uses a 0.1× step.</li>
 *   <li>Optional inline ± stepper buttons, a unit badge, and an optional
 *       pretty-formatter used when the field is not focused.</li>
 *   <li>Commits on Enter or focus loss; reverts to the current value on Escape.</li>
 *   <li>Visual {@code .invalid} pseudo-state when the parsed value is out of range.</li>
 * </ul>
 */
public final class NumberField extends HBox {
    private final DoubleProperty value = new SimpleDoubleProperty();
    private final TextField text = new TextField();
    private final Label unitLabel = new Label();
    private final Button upButton = new Button("\u25B4");
    private final Button downButton = new Button("\u25BE");

    private double min = Double.NEGATIVE_INFINITY;
    private double max = Double.POSITIVE_INFINITY;
    private double step = 1.0;
    private int decimals = -1;
    private boolean scientific = false;
    private DoubleFunction<String> customFormatter;
    private boolean suppressUpdate = false;

    public NumberField() {
        getStyleClass().add("number-field");
        setAlignment(Pos.CENTER_LEFT);

        text.getStyleClass().add("number-field-text");
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setPrefColumnCount(6);

        unitLabel.getStyleClass().add("unit-label");
        unitLabel.setVisible(false);
        unitLabel.setManaged(false);

        upButton.getStyleClass().add("stepper");
        downButton.getStyleClass().add("stepper");
        upButton.setFocusTraversable(false);
        downButton.setFocusTraversable(false);

        var stepperBox = new VBox(upButton, downButton);
        stepperBox.getStyleClass().add("stepper-box");
        stepperBox.setSpacing(0);

        upButton.setPrefHeight(13);
        downButton.setPrefHeight(13);

        getChildren().addAll(text, unitLabel, stepperBox);

        upButton.setOnAction(e -> adjust(+1, 1.0));
        downButton.setOnAction(e -> adjust(-1, 1.0));

        text.setOnKeyPressed(e -> {
            double mult = e.isShiftDown() ? 10.0 : e.isAltDown() ? 0.1 : 1.0;
            if (e.getCode() == KeyCode.UP) { adjust(+1, mult); e.consume(); }
            else if (e.getCode() == KeyCode.DOWN) { adjust(-1, mult); e.consume(); }
            else if (e.getCode() == KeyCode.ENTER) { commit(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { renderValue(); e.consume(); }
        });

        text.focusedProperty().addListener((obs, o, focused) -> {
            if (focused) {
                text.selectAll();
            } else {
                commit();
            }
        });

        value.addListener((obs, o, n) -> {
            if (suppressUpdate) return;
            renderValue();
        });

        renderValue();
    }

    // ---------- Fluent configuration ----------

    public NumberField range(double min, double max) {
        this.min = min;
        this.max = max;
        return this;
    }

    public NumberField step(double step) {
        this.step = step;
        return this;
    }

    public NumberField decimals(int decimals) {
        this.decimals = decimals;
        this.scientific = false;
        renderValue();
        return this;
    }

    public NumberField scientific() {
        this.scientific = true;
        this.decimals = -1;
        renderValue();
        return this;
    }

    public NumberField formatter(DoubleFunction<String> formatter) {
        this.customFormatter = formatter;
        renderValue();
        return this;
    }

    public NumberField unit(String unit) {
        if (unit == null || unit.isEmpty()) {
            unitLabel.setVisible(false);
            unitLabel.setManaged(false);
        } else {
            unitLabel.setText(unit);
            unitLabel.setVisible(true);
            unitLabel.setManaged(true);
        }
        return this;
    }

    public NumberField prefColumnCount(int columns) {
        text.setPrefColumnCount(columns);
        return this;
    }

    public NumberField initial(double v) {
        setValue(v);
        return this;
    }

    // ---------- Properties ----------

    public DoubleProperty valueProperty() { return value; }
    public double getValue() { return value.get(); }
    public void setValue(double v) {
        value.set(clampFinite(v));
    }

    /** Silently update without firing listeners. Used by the editor during rebuilds. */
    public void setValueQuiet(double v) {
        suppressUpdate = true;
        try {
            value.set(clampFinite(v));
            renderValue();
        } finally {
            suppressUpdate = false;
        }
    }

    /**
     * Two-way bind this field to a {@link javafx.beans.property.DoubleProperty}:
     * push field edits into the property, pull external property changes back
     * via {@link #setValueQuiet(double)} (so we don't loop). Initial value
     * comes from the property.
     */
    public NumberField bindBidirectional(javafx.beans.property.DoubleProperty property) {
        setValue(property.get());
        valueProperty().addListener((obs, o, n) -> { if (n != null) property.set(n.doubleValue()); });
        property.addListener((obs, o, n) -> setValueQuiet(n.doubleValue()));
        return this;
    }

    /** Two-way bind this field to an {@link javafx.beans.property.IntegerProperty}. */
    public NumberField bindBidirectional(javafx.beans.property.IntegerProperty property) {
        setValue(property.get());
        valueProperty().addListener((obs, o, n) -> { if (n != null) property.set(n.intValue()); });
        property.addListener((obs, o, n) -> setValueQuiet(n.intValue()));
        return this;
    }

    // ---------- Internals ----------

    private double clampFinite(double v) {
        if (!Double.isFinite(v)) return 0;
        return MathUtil.clamp(v, min, max);
    }

    private void adjust(int direction, double mult) {
        double currentStep = step * mult;
        // First commit any in-progress edit, so typing "+1.2" then Up still adjusts correctly.
        commitSilent();
        double next = clampFinite(value.get() + direction * currentStep);
        value.set(next);
    }

    private void commit() {
        Double parsed = parseOrNull(text.getText());
        if (parsed == null) {
            renderValue();
            pseudoClassStateChanged(INVALID_STATE, false);
            return;
        }
        double clamped = clampFinite(parsed);
        boolean outOfRange = Math.abs(clamped - parsed) > 1e-15;
        pseudoClassStateChanged(INVALID_STATE, outOfRange);
        value.set(clamped);
        renderValue();
    }

    private void commitSilent() {
        Double parsed = parseOrNull(text.getText());
        if (parsed == null) return;
        suppressUpdate = true;
        try {
            value.set(clampFinite(parsed));
        } finally {
            suppressUpdate = false;
        }
    }

    private static Double parseOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("+")) s = s.substring(1);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void renderValue() {
        if (text.isFocused()) return;
        text.setText(format(value.get()));
        pseudoClassStateChanged(INVALID_STATE, false);
    }

    private String format(double v) {
        if (customFormatter != null) return customFormatter.apply(v);
        if (scientific) {
            if (v == 0) return "0";
            double abs = Math.abs(v);
            if (abs >= 1e4 || abs < 1e-3) return String.format(Locale.US, "%.3g", v);
            // Within a reasonable decimal range, show as a regular number.
        }
        int d = decimals;
        if (d < 0) {
            double abs = Math.abs(v);
            if (abs == 0) d = 2;
            else if (abs >= 100) d = 2;
            else if (abs >= 10) d = 3;
            else if (abs >= 1) d = 4;
            else if (abs >= 1e-2) d = 5;
            else return String.format(Locale.US, "%.4g", v);
        }
        return String.format(Locale.US, "%." + d + "f", v);
    }

    private static final javafx.css.PseudoClass INVALID_STATE = javafx.css.PseudoClass.getPseudoClass("invalid");
}
