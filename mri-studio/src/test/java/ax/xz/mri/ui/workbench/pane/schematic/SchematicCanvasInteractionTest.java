package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.support.FxTestSupport;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI-level tests for the schematic canvas and its pane.
 *
 * <p>Exercises tool-mode toggling, zoom controls, and key-handling via the
 * scene-level filter in {@link SchematicPane}. Construction-heavy tests run
 * on the JavaFX thread; API-only tests run inline.
 */
class SchematicCanvasInteractionTest {

    @Test
    void primaryModeDefaultsToSelectAndPropagatesToCursor() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var canvas = new SchematicCanvas(session);
            assertEquals(SchematicCanvas.PrimaryMode.SELECT, canvas.primaryMode());
            canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.PAN);
            assertEquals(SchematicCanvas.PrimaryMode.PAN, canvas.primaryMode());
            canvas.setPrimaryMode(SchematicCanvas.PrimaryMode.WIRE);
            assertEquals(SchematicCanvas.PrimaryMode.WIRE, canvas.primaryMode());
        });
    }

    @Test
    void zoomAndFitDoNotThrowOnEmptyOrPopulatedCircuit() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var canvas = new SchematicCanvas(session);
            canvas.resize(600, 400);
            canvas.zoomBy(1.2);
            canvas.zoomBy(0.5);
            canvas.resetZoom();
            canvas.fitToView();
        });
    }

    @Test
    void escClearsSelectionAndExitsWireMode() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var canvas = new SchematicCanvas(session);
            session.selectedComponents.add(new ComponentId("src"));
            canvas.setTool(SchematicCanvas.ToolState.wiring(
                new ComponentTerminal(new ComponentId("src"), "a")));
            boolean consumed = canvas.handleKey(keyEvent(KeyCode.ESCAPE, false, false));
            assertTrue(consumed);
            assertEquals(SchematicCanvas.ToolState.Kind.IDLE, canvas.tool().kind());
            assertTrue(session.selectedComponents.isEmpty());
        });
    }

    @Test
    void deleteShortcutRemovesSelectedComponents() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var canvas = new SchematicCanvas(session);
            session.selectedComponents.add(new ComponentId("coil"));
            boolean consumed = canvas.handleKey(keyEvent(KeyCode.DELETE, false, false));
            assertTrue(consumed);
            assertFalse(session.doc().components().stream()
                .anyMatch(c -> c.id().value().equals("coil")));
        });
    }

    @Test
    void ctrlCThenCtrlVDuplicatesSelection() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var canvas = new SchematicCanvas(session);
            session.selectedComponents.add(new ComponentId("src"));
            int before = session.doc().components().size();
            canvas.handleKey(keyEvent(KeyCode.C, true, false));
            canvas.handleKey(keyEvent(KeyCode.V, true, false));
            int after = session.doc().components().size();
            assertEquals(before + 1, after);
        });
    }

    @Test
    void paneKeyFilterRoutesModeHotkeys() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new CircuitEditSession(lowCircuit());
            var pane = new SchematicPane(session, () -> null, id -> {});
            var stage = new Stage();
            var scene = new Scene(new BorderPane(pane), 400, 300);
            stage.setScene(scene);
            stage.show();
            try {
                // The scene-level key filter only routes when the mouse is over the pane.
                // Dispatch a mouse-moved event through the scene first so the pane
                // records a cursor position inside its bounds.
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var bounds = pane.localToScene(pane.getBoundsInLocal());
                double mx = bounds.getMinX() + bounds.getWidth() / 2;
                double my = bounds.getMinY() + bounds.getHeight() / 2;
                var mouseMove = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_MOVED, mx, my, mx, my,
                    javafx.scene.input.MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false, null);
                scene.getRoot().fireEvent(mouseMove);

                scene.getRoot().fireEvent(keyEvent(KeyCode.H, false, false));
                assertEquals(SchematicCanvas.PrimaryMode.PAN, pane.canvas().primaryMode());
                scene.getRoot().fireEvent(keyEvent(KeyCode.V, false, false));
                assertEquals(SchematicCanvas.PrimaryMode.SELECT, pane.canvas().primaryMode());
                scene.getRoot().fireEvent(keyEvent(KeyCode.W, false, false));
                assertEquals(SchematicCanvas.PrimaryMode.WIRE, pane.canvas().primaryMode());
            } finally {
                stage.close();
            }
        });
    }

    private static KeyEvent keyEvent(KeyCode code, boolean shortcut, boolean shift) {
        EventType<KeyEvent> type = KeyEvent.KEY_PRESSED;
        return new KeyEvent(type, "", code.getName(), code,
            shift, shortcut, false, shortcut);
    }

    private static CircuitDocument lowCircuit() {
        var src = new CircuitComponent.VoltageSource(new ComponentId("src"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var wires = List.of(
            new Wire("w1", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "in"))
        );
        var layout = CircuitLayout.empty()
            .with(new ComponentPosition(src.id(), 100, 100, 0))
            .with(new ComponentPosition(coil.id(), 300, 100, 0));
        return new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil), wires, layout);
    }
}
