package ax.xz.mri.ui.workbench.framework;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Section / field / row primitives shared by every document editor in the
 * workbench — substance, procedure, eigenfield, simulation config.
 *
 * <p>The visual language is a SolidWorks / HFSS-style sectioned card:
 * <ul>
 *   <li>a bold header with optional accent ("PHYSICS", "GEOMETRY", "PORTS");
 *   <li>an optional one-line hint that explains the section's role;
 *   <li>a body that hosts field rows or arbitrary content.
 * </ul>
 *
 * <p>Every editor that uses this helper feels the same: same row alignment,
 * same label gutter, same unit suffix style, same heading weight, same
 * inter-section spacing. Restyling the whole app means touching one CSS
 * block in {@code studio.css}, not every editor.
 *
 * <p>The helper is presentation-only — it doesn't know about documents,
 * mutations, or the project state. Callers wire callbacks (typically
 * {@code DocumentEditor#apply}) into the {@code onChange} hooks each field
 * exposes.
 */
public final class EditorSection {
    private EditorSection() {}

    /** Label column width — keeps every row in an editor aligned. */
    public static final double LABEL_WIDTH = 156;

    /** Standard pixel width for an in-row text field; works for most numeric inputs. */
    public static final double FIELD_WIDTH = 130;

    /** Standard pixel width for a unit suffix; rendered to the right of a numeric input. */
    public static final double UNIT_WIDTH = 60;

    /* ── Section card ──────────────────────────────────────────────────── */

    /**
     * Build a card-style section with a header. Use {@link #addRow} or
     * {@link #addNode} to populate the body.
     *
     * @param title    short uppercase-style caption (e.g. "Identification", "Physics", "Ports")
     */
    public static SectionCard section(String title) {
        return new SectionCard(title, null);
    }

    /** Like {@link #section(String)} but with a small body-text hint under the header. */
    public static SectionCard section(String title, String hint) {
        return new SectionCard(title, hint);
    }

    /** A self-contained section card with header + optional hint + ordered body. */
    public static final class SectionCard {
        private final VBox body = new VBox();
        private final VBox root;

        SectionCard(String title, String hint) {
            var header = new Label(title);
            header.getStyleClass().add("editor-section-header");

            root = new VBox();
            root.getStyleClass().add("editor-section");
            root.getChildren().add(header);
            if (hint != null && !hint.isBlank()) {
                var h = new Label(hint);
                h.getStyleClass().add("editor-section-hint");
                h.setWrapText(true);
                root.getChildren().add(h);
            }
            body.getStyleClass().add("editor-section-body");
            root.getChildren().add(body);
        }

        public SectionCard addRow(String label, Node control) {
            body.getChildren().add(EditorSection.row(label, control));
            return this;
        }

        public SectionCard addRow(String label, Node control, String unit) {
            body.getChildren().add(EditorSection.row(label, control, unit));
            return this;
        }

        public SectionCard addNode(Node node) {
            body.getChildren().add(node);
            return this;
        }

        /** Replace the body — used by editors that switch the substance subtype dynamically. */
        public SectionCard replaceBody(List<? extends Node> nodes) {
            body.getChildren().setAll(nodes);
            return this;
        }

        public Node node() { return root; }

        /** Body for callers that want fine-grained add/remove. */
        public VBox body() { return body; }
    }

    /* ── Field rows ────────────────────────────────────────────────────── */

    public static HBox row(String label, Node control) {
        return row(label, control, null);
    }

    public static HBox row(String label, Node control, String unit) {
        var row = new HBox();
        row.getStyleClass().add("editor-field-row");

        var l = new Label(label);
        l.getStyleClass().add("editor-field-label");
        l.setPrefWidth(LABEL_WIDTH);
        l.setMinWidth(LABEL_WIDTH);
        row.getChildren().add(l);

        if (control instanceof Region r) {
            HBox.setHgrow(r, Priority.NEVER);
            if (r.getPrefWidth() <= 0) r.setPrefWidth(FIELD_WIDTH);
        }
        row.getChildren().add(control);

        if (unit != null && !unit.isBlank()) {
            var u = new Label(unit);
            u.getStyleClass().add("editor-field-unit");
            u.setPrefWidth(UNIT_WIDTH);
            row.getChildren().add(u);
        }

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);
        return row;
    }

    /** A horizontal divider — use sparingly inside long sections. */
    public static Node divider() {
        var sep = new Region();
        sep.getStyleClass().add("editor-section-divider");
        sep.setPrefHeight(1);
        sep.setMaxHeight(1);
        return sep;
    }

    /* ── Field primitives — emit just the input, callers wrap with row() ─ */

    /**
     * Numeric field with format-on-blur and commit-on-Enter semantics.
     * Bad input reverts to the last committed value.
     */
    public static TextField doubleField(double initial, Consumer<Double> onChange) {
        var field = new TextField(formatDouble(initial));
        field.getStyleClass().add("editor-field");
        field.setPrefWidth(FIELD_WIDTH);
        double[] lastGood = { initial };
        Runnable commit = () -> {
            try {
                double v = Double.parseDouble(field.getText().trim());
                if (v != lastGood[0]) {
                    lastGood[0] = v;
                    onChange.accept(v);
                }
                field.setText(formatDouble(v));
            } catch (NumberFormatException ex) {
                field.setText(formatDouble(lastGood[0]));
            }
        };
        field.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commit.run(); });
        field.setOnAction(e -> commit.run());
        return field;
    }

    /** Long field — same semantics as {@link #doubleField}. */
    public static TextField longField(long initial, Consumer<Long> onChange) {
        var field = new TextField(Long.toString(initial));
        field.getStyleClass().add("editor-field");
        field.setPrefWidth(FIELD_WIDTH);
        long[] lastGood = { initial };
        Runnable commit = () -> {
            try {
                long v = Long.parseLong(field.getText().trim());
                if (v != lastGood[0]) {
                    lastGood[0] = v;
                    onChange.accept(v);
                }
                field.setText(Long.toString(v));
            } catch (NumberFormatException ex) {
                field.setText(Long.toString(lastGood[0]));
            }
        };
        field.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commit.run(); });
        field.setOnAction(e -> commit.run());
        return field;
    }

    /** String field that commits on focus loss or Enter. */
    public static TextField stringField(String initial, Consumer<String> onChange) {
        var field = new TextField(initial == null ? "" : initial);
        field.getStyleClass().add("editor-field");
        field.setPrefWidth(FIELD_WIDTH);
        field.focusedProperty().addListener((obs, o, focused) -> { if (!focused) onChange.accept(field.getText()); });
        field.setOnAction(e -> onChange.accept(field.getText()));
        return field;
    }

    /** Read-only field — displayed in the same shape as editable inputs but with greyer text. */
    public static Label readOnlyValue(String text) {
        var l = new Label(text == null ? "" : text);
        l.getStyleClass().add("editor-field-value");
        l.setPrefWidth(FIELD_WIDTH);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    @SafeVarargs
    public static <T> ComboBox<T> enumField(T selected, Consumer<T> onChange, T... values) {
        var combo = new ComboBox<>(FXCollections.observableArrayList(Arrays.asList(values)));
        combo.setValue(selected);
        combo.getStyleClass().add("editor-field");
        combo.setPrefWidth(FIELD_WIDTH);
        combo.setOnAction(e -> onChange.accept(combo.getValue()));
        return combo;
    }

    /* ── Badges / pills ────────────────────────────────────────────────── */

    /**
     * Coloured pill used to mark the kind of a document (e.g. "NV ENSEMBLE",
     * "PROTON BLOCH", "ITERATIVE PROCEDURE"). Use sparingly — typically the
     * page header carries one badge to anchor the user's mental model.
     */
    public static Label kindBadge(String text) {
        var l = new Label(text == null ? "" : text);
        l.getStyleClass().add("editor-kind-badge");
        return l;
    }

    /* ── Page header ───────────────────────────────────────────────────── */

    /**
     * Compact page header used at the top of every document-editor pane.
     * Layout: badge + bold name + subtitle. The {@code subtitle} should be
     * a short noun phrase describing the document's role.
     */
    public static HBox pageHeader(String badge, String name, String subtitle) {
        var row = new HBox();
        row.getStyleClass().add("editor-page-header");
        row.setAlignment(Pos.CENTER_LEFT);

        if (badge != null && !badge.isBlank()) {
            row.getChildren().add(kindBadge(badge));
        }
        var nameLabel = new Label(name == null ? "" : name);
        nameLabel.getStyleClass().add("editor-page-name");
        row.getChildren().add(nameLabel);

        if (subtitle != null && !subtitle.isBlank()) {
            var sep = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
            sep.getStyleClass().add("editor-page-separator");
            row.getChildren().add(sep);
            var sub = new Label(subtitle);
            sub.getStyleClass().add("editor-page-subtitle");
            row.getChildren().add(sub);
        }
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);
        return row;
    }

    /* ── Misc helpers ─────────────────────────────────────────────────── */

    public static VBox stack(SectionCard... cards) {
        var vbox = new VBox();
        vbox.getStyleClass().add("editor-section-stack");
        for (var c : cards) vbox.getChildren().add(c.node());
        return vbox;
    }

    private static String formatDouble(double v) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        if (abs >= 1e-3 && abs < 1e6) return String.format("%.6g", v);
        return String.format("%.3e", v);
    }

    /* ── Binding-friendly read-only value ──────────────────────────────── */

    /**
     * Read-only label whose text follows a string property. Useful when the
     * editor wants a value that reflects an underlying document field —
     * e.g. an auto-derived port count — that the user can't edit.
     */
    public static Label boundReadOnlyValue(javafx.beans.value.ObservableValue<String> observable) {
        var l = new Label();
        l.getStyleClass().add("editor-field-value");
        l.textProperty().bind(Bindings.createStringBinding(() -> {
            var v = observable.getValue();
            return v == null ? "" : v;
        }, observable));
        return l;
    }
}
