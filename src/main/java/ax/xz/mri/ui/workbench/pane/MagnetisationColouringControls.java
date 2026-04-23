package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.viewmodel.MagnetisationColouringViewModel;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;

/** Shared pane controls for configuring magnetisation colouring. */
final class MagnetisationColouringControls {
    private MagnetisationColouringControls() {
    }

    static MenuButton newMenuButton(MagnetisationColouringViewModel colouring) {
        var button = new MenuButton();
        button.getStyleClass().add("pane-tool-menu");
        button.setFocusTraversable(false);
        button.textProperty().bind(Bindings.createStringBinding(
            () -> "Colour: " + colouring.summaryLabel(),
            colouring.hueSource,
            colouring.brightnessSource
        ));
        var tooltip = new Tooltip();
        tooltip.textProperty().bind(Bindings.createStringBinding(
            () -> "Choose where hue and brightness come from for magnetisation views.",
            colouring.hueSource,
            colouring.brightnessSource
        ));
        button.setTooltip(tooltip);
        button.getItems().setAll(
            newHueMenu(colouring),
            newBrightnessMenu(colouring)
        );
        return button;
    }

    static Menu newMenu(MagnetisationColouringViewModel colouring) {
        var menu = new Menu("Colouring");
        menu.getItems().addAll(
            newHueMenu(colouring),
            newBrightnessMenu(colouring)
        );
        return menu;
    }

    private static Menu newHueMenu(MagnetisationColouringViewModel colouring) {
        var menu = new Menu("Hue");
        var group = new ToggleGroup();
        for (var source : MagnetisationColouringViewModel.HueSource.values()) {
            var item = new RadioMenuItem(source.displayName());
            item.setToggleGroup(group);
            item.setUserData(source);
            if (colouring.hueSource.get() == source) item.setSelected(true);
            menu.getItems().add(item);
        }
        installBinding(
            group,
            colouring.hueSource.get(),
            colouring.hueSource::set,
            colouring.hueSource
        );
        return menu;
    }

    private static Menu newBrightnessMenu(MagnetisationColouringViewModel colouring) {
        var menu = new Menu("Brightness");
        var group = new ToggleGroup();
        for (var source : MagnetisationColouringViewModel.BrightnessSource.values()) {
            var item = new RadioMenuItem(source.displayName());
            item.setToggleGroup(group);
            item.setUserData(source);
            if (colouring.brightnessSource.get() == source) item.setSelected(true);
            menu.getItems().add(item);
        }
        installBinding(
            group,
            colouring.brightnessSource.get(),
            colouring.brightnessSource::set,
            colouring.brightnessSource
        );
        return menu;
    }

    private static <T> void installBinding(
        ToggleGroup group,
        T initialValue,
        java.util.function.Consumer<T> propertySetter,
        javafx.beans.property.ObjectProperty<T> property
    ) {
        selectToggle(group, initialValue);
        group.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                @SuppressWarnings("unchecked")
                var selected = (T) newValue.getUserData();
                if (selected != property.get()) propertySetter.accept(selected);
            }
        });
        property.addListener((obs, oldValue, newValue) -> selectToggle(group, newValue));
    }

    private static void selectToggle(ToggleGroup group, Object value) {
        for (Toggle toggle : group.getToggles()) {
            if (toggle.getUserData() == value) {
                group.selectToggle(toggle);
                return;
            }
        }
        group.selectToggle(null);
    }
}
