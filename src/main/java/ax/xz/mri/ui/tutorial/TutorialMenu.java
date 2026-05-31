package ax.xz.mri.ui.tutorial;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import java.util.function.Consumer;

/**
 * Builds the Help ▸ Tutorials submenu from {@link TutorialLibrary#all()}.
 * Caller hands in a {@code Consumer<Tutorial>} that starts the chosen one
 * via the host's {@link TutorialRunner}.
 */
public final class TutorialMenu {
    private TutorialMenu() {}

    public static Menu buildHelpMenu(Consumer<Tutorial> launch) {
        var tutorialsMenu = new Menu("Tutorials");
        for (var t : TutorialLibrary.all()) {
            var item = new MenuItem(t.title());
            item.setOnAction(e -> launch.accept(t));
            tutorialsMenu.getItems().add(item);
        }
        var helpMenu = new Menu("Help");
        helpMenu.getItems().add(tutorialsMenu);
        return helpMenu;
    }
}
