package com.shelfwise.desktop;

import com.shelfwise.core.AppInfo;
import com.shelfwise.core.model.User;
import com.shelfwise.core.storage.Settings;
import com.shelfwise.core.storage.SettingsFile;
import com.shelfwise.desktop.ui.FirstRunController;
import com.shelfwise.desktop.ui.LockController;
import com.shelfwise.desktop.ui.MainController;
import com.shelfwise.desktop.ui.Session;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The JavaFX application: decides what the window shows first.
 * <ol>
 *   <li>No folder or user yet: the first-run page (E44).</li>
 *   <li>A Local user: the lock screen, every launch (E105).</li>
 *   <li>Then the main window, with the collection open.</li>
 * </ol>
 * The scene's root is a {@link StackPane}, so first run and the lock screen
 * are simply laid over the whole window, sidebar included, and removed
 * when done.
 */
public class ShelfWiseApp extends Application {

    private Session session;
    private Scene scene;
    private StackPane root;

    @Override
    public void start(Stage stage) {
        try {
            session = new Session(new SettingsFile(Session.settingsPath()));
        } catch (IOException e) {
            fail("The settings could not be read", e);
            return;
        }

        root = new StackPane();
        scene = new Scene(root, 1320, 840);
        // Slot 0 is the layout, slot 1 the theme; MainController swaps slot 1.
        scene.getStylesheets().add(css("app.css"));
        scene.getStylesheets().add(css(session.getSettings().getTheme() == Settings.Theme.DARK
                ? "theme-dark.css" : "theme-light.css"));

        stage.setTitle(AppInfo.getDisplayName());
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();

        if (session.getSettings().isFirstRun()) {
            showFirstRun();
        } else {
            try {
                session.openCollection(false);
            } catch (IOException e) {
                fail("The collection could not be opened", e);
                return;
            }
            unlockThenShowMain();
        }
    }

    /** D22/E44: the choices are written only once the collection exists, so quitting part-way leaves nothing. */
    private void showFirstRun() {
        FXMLLoader loader = new FXMLLoader(ShelfWiseApp.class.getResource("ui/first-run-view.fxml"));
        final Parent page = load(loader);
        root.getChildren().add(page);
        FirstRunController firstRun = loader.getController();
        firstRun.open(new FirstRunController.Done() {
            @Override
            public void finished(User user, Path folder, boolean sample) {
                session.getSettings().setUser(user);
                session.getSettings().setDataFolder(folder);
                try {
                    session.openCollection(sample);
                    session.saveSettings();
                } catch (IOException e) {
                    // Stay on first run, so another folder can be chosen.
                    error("ShelfWise could not set up that folder", e.getMessage());
                    return;
                }
                root.getChildren().remove(page);
                showMain();
            }
        });
    }

    /** E105: a Local user unlocks first; a Guest goes straight in. */
    private void unlockThenShowMain() {
        User user = session.getSettings().getUser();
        // requiresPassword() is answered by the real subclass, GuestUser or
        // LocalUser: the app never checks which kind of user it has.
        if (!user.requiresPassword()) {
            showMain();
            return;
        }
        FXMLLoader loader = new FXMLLoader(ShelfWiseApp.class.getResource("ui/lock-view.fxml"));
        final Parent page = load(loader);
        root.getChildren().add(page);
        LockController lock = loader.getController();
        lock.open(user, new LockController.Unlocked() {
            @Override
            public void unlocked() {
                root.getChildren().remove(page);
                showMain();
            }
        });
    }

    /** The main window. Built only after unlocking, so nothing of the collection sits behind the lock. */
    private void showMain() {
        FXMLLoader loader = new FXMLLoader(ShelfWiseApp.class.getResource("ui/main-view.fxml"));
        root.getChildren().add(0, load(loader));
        MainController main = loader.getController();
        main.installShortcuts(scene);
        main.start(session);
    }

    private static Parent load(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException e) {
            // Our own FXML failing to load is a bug, not something the user can fix.
            throw new IllegalStateException("Could not load " + loader.getLocation() + ": " + e.getMessage(), e);
        }
    }

    private static String css(String file) {
        return ShelfWiseApp.class.getResource("css/" + file).toExternalForm();
    }

    private static void error(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    /** A problem the app cannot start past: say what it was, then close. */
    private static void fail(String header, Exception e) {
        error(header, e.getMessage());
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
