package ax.xz.mri.ui.tutorial;

import module javafx.controls;

import java.util.function.Consumer;

/**
 * Empty-state pane shown in the workbench's centre when no project is open.
 * Two prominent tutorial buttons + an "Open Project…" fallback.
 *
 * <p>The shell mounts this whenever the active project has no documents AND
 * has the default "Untitled Project" manifest name — i.e. the user has
 * just launched the studio. Any document mutation (or an explicit
 * {@code openProject(...)}) replaces it with the normal workbench.
 *
 * <p>Buttons register with {@link UiAnchors} so tutorials can spotlight
 * them — the welcome pane is itself the first stop on a tutorial chain.
 */
public final class WelcomePane extends StackPane {

    public WelcomePane(Runnable openProjectAction, Consumer<Tutorial> startTutorial) {
        getStyleClass().add("welcome-pane");
        setPadding(new Insets(48));
        setStyle("-fx-background-color: #f8fafc;");

        var heading = new Label("Welcome to mri-studio");
        heading.setFont(Font.font("System", FontWeight.BOLD, 28));
        heading.setStyle("-fx-text-fill: #1a2530;");

        var subtitle = new Label("Get started by following a guided tutorial, or open an existing project.");
        subtitle.setStyle("-fx-text-fill: #5d6f88; -fx-font-size: 13;");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(560);

        var nvButton = tutorialCard(
            "NV adaptive coherent",
            "Build an NV-centre diamond simulation and the adaptive Bayesian "
          + "procedure that reconstructs Bz(x) from Ramsey blocks.",
            () -> startTutorial.accept(TutorialLibrary.NV_COHERENT));

        var blochButton = tutorialCard(
            "Low-field MRI + CPMG",
            "Set up a low-field proton MRI simulation and a CPMG echo train "
          + "for T2 measurement.",
            () -> startTutorial.accept(TutorialLibrary.BLOCH_CPMG));

        var cardRow = new HBox(20, nvButton, blochButton);
        cardRow.setAlignment(Pos.CENTER);
        // HBox fills children to its height by default — that's what was
        // stretching the cards into full-height columns. Keep them at their
        // own (content) height instead.
        cardRow.setFillHeight(false);
        cardRow.setMaxHeight(Region.USE_PREF_SIZE);

        var openProjectBtn = new Button("Open existing project…");
        openProjectBtn.setOnAction(e -> openProjectAction.run());
        openProjectBtn.setFocusTraversable(false);
        openProjectBtn.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-text-fill: #1a73e8;"
          + "-fx-font-size: 12;"
          + "-fx-underline: true;");

        var content = new VBox(18, heading, subtitle, new Separator(), cardRow, openProjectBtn);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(720);
        // Pin to preferred size so the enclosing StackPane centres the block
        // instead of stretching the cards to fill the whole pane height.
        content.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(content, Pos.CENTER);
        getChildren().add(content);
    }

    private static Button tutorialCard(String title, String body, Runnable onClick) {
        var card = new Button();
        var titleLbl = new Label(title);
        titleLbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 14));
        titleLbl.setStyle("-fx-text-fill: #1a73e8;");
        var bodyLbl = new Label(body);
        bodyLbl.setStyle("-fx-text-fill: #2b2f37; -fx-font-size: 11.5;");
        bodyLbl.setWrapText(true);
        bodyLbl.setMaxWidth(260);
        var inner = new VBox(8, titleLbl, bodyLbl);
        inner.setAlignment(Pos.TOP_LEFT);
        inner.setPadding(new Insets(16, 18, 16, 18));
        inner.setPrefWidth(264);
        card.setGraphic(inner);
        card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        card.setFocusTraversable(false);
        // Fixed-size cards — not full-height columns.
        card.setMinSize(300, 150);
        card.setPrefSize(300, 150);
        card.setMaxSize(300, 150);
        card.setStyle(
            "-fx-background-color: #ffffff;"
          + "-fx-background-radius: 10;"
          + "-fx-border-color: #d8dde2;"
          + "-fx-border-radius: 10;"
          + "-fx-border-width: 1;"
          + "-fx-padding: 0;"
          + "-fx-cursor: hand;");
        card.setOnMouseEntered(e -> card.setStyle(
            "-fx-background-color: #ffffff;"
          + "-fx-background-radius: 10;"
          + "-fx-border-color: #1a73e8;"
          + "-fx-border-radius: 10;"
          + "-fx-border-width: 1.5;"
          + "-fx-padding: 0;"
          + "-fx-cursor: hand;"));
        card.setOnMouseExited(e -> card.setStyle(
            "-fx-background-color: #ffffff;"
          + "-fx-background-radius: 10;"
          + "-fx-border-color: #d8dde2;"
          + "-fx-border-radius: 10;"
          + "-fx-border-width: 1;"
          + "-fx-padding: 0;"
          + "-fx-cursor: hand;"));
        card.setOnAction(e -> onClick.run());
        return card;
    }
}
